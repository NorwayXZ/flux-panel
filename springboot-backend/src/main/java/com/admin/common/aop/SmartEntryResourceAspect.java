package com.admin.common.aop;

import com.admin.common.dto.ForwardUpdateDto;
import com.admin.common.dto.NodeUpdateDto;
import com.admin.common.dto.TunnelUpdateDto;
import com.admin.common.lang.R;
import com.admin.service.SmartEntryMutationLocks;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Holds the resource lock outside the service transaction, including its commit. */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 50)
public class SmartEntryResourceAspect {
    private final JdbcTemplate jdbc;
    private final SmartEntryMutationLocks locks;
    public SmartEntryResourceAspect(JdbcTemplate jdbc, SmartEntryMutationLocks locks) {
        this.jdbc = jdbc;
        this.locks = locks;
    }

    @Around("execution(* com.admin.service.impl.ForwardServiceImpl.updateForward(..)) || "
            + "execution(* com.admin.service.impl.ForwardServiceImpl.updateManagedForward(..)) || "
            + "execution(* com.admin.service.impl.ForwardServiceImpl.deleteForward(..)) || "
            + "execution(* com.admin.service.impl.ForwardServiceImpl.deleteManagedForward(..)) || "
            + "execution(* com.admin.service.impl.ForwardServiceImpl.forceDeleteForward(..)) || "
            + "execution(* com.admin.service.impl.NodeServiceImpl.updateNode(..)) || "
            + "execution(* com.admin.service.impl.NodeServiceImpl.deleteNode(..)) || "
            + "execution(* com.admin.service.impl.TunnelServiceImpl.updateTunnel(..)) || "
            + "execution(* com.admin.service.impl.TunnelServiceImpl.deleteTunnel(..)) || "
            + "execution(* com.admin.service.impl.UserServiceImpl.deleteUser(..))")
    public Object protect(ProceedingJoinPoint joinPoint) {
        return locks.withLock(() -> {
            Object argument = joinPoint.getArgs()[0];
            Long id = argument instanceof ForwardUpdateDto dto ? dto.getId()
                    : argument instanceof NodeUpdateDto dto ? dto.getId()
                    : argument instanceof TunnelUpdateDto dto ? dto.getId() : (Long) argument;
            String owner = joinPoint.getSignature().getDeclaringTypeName();
            String predicate = "(r.forward_id=? OR r.current_forward_id=?)";
            Object[] parameters = {id, id};
            if (owner.contains("NodeService")) {
                if (argument instanceof NodeUpdateDto dto) {
                    List<Map<String, Object>> nodes = jdbc.queryForList("SELECT ip,server_ip,port_sta,port_end FROM node WHERE id=?", id);
                    if (!nodes.isEmpty() && Objects.equals(nodes.get(0).get("ip"), dto.getIp())
                            && Objects.equals(nodes.get(0).get("server_ip"), dto.getServerIp())
                            && Objects.equals(nodes.get(0).get("port_sta"), dto.getPortSta())
                            && Objects.equals(nodes.get(0).get("port_end"), dto.getPortEnd())) return proceed(joinPoint);
                }
                predicate = "(r.entry_node_id=? OR t.in_node_id=? OR t.out_node_id=? OR "
                        + "FIND_IN_SET(CAST(? AS CHAR),REPLACE(REPLACE(REPLACE(COALESCE(t.node_path,''),'[',''),']',''),' ',''))>0)";
                parameters = new Object[]{id, id, id, id};
            } else if (owner.contains("TunnelService")) {
                predicate = "f.tunnel_id=?";
                parameters = new Object[]{id};
            } else if (owner.contains("UserService")) {
                predicate = "f.user_id=?";
                parameters = new Object[]{id};
            }
            List<String> groups = jdbc.queryForList("SELECT DISTINCT g.name FROM smart_entry_group g JOIN smart_entry_route r ON r.group_id=g.id "
                    + "LEFT JOIN forward f ON f.id=r.forward_id OR f.id=r.current_forward_id "
                    + "LEFT JOIN tunnel t ON t.id=f.tunnel_id WHERE " + predicate, String.class, parameters);
            if (!groups.isEmpty()) return R.err("资源正在被三网优化策略引用。请先在三网优化中替换或解除引用，"
                    + "并等待 DNS 清理完成，再编辑或删除资源");
            return proceed(joinPoint);
        });
    }

    private Object proceed(ProceedingJoinPoint point) {
        try { return point.proceed(); }
        catch (RuntimeException | Error e) { throw e; }
        catch (Throwable e) { throw new IllegalStateException(e); }
    }
}
