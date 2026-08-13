import React, { useState, useEffect } from 'react';
import { Card, CardBody } from "@heroui/card";
import { Button } from "@heroui/button";
import { Modal, ModalContent, ModalHeader, ModalBody, ModalFooter, useDisclosure } from "@heroui/modal";
import { Input } from "@heroui/input";
import { toast } from 'react-hot-toast';
import { useNavigate } from 'react-router-dom';
import { isWebViewFunc } from '@/utils/panel';
import { siteConfig } from '@/config/site';
import { updatePassword } from '@/api';
import { safeLogout } from '@/utils/logout';
import { BookOpen, Boxes, RadioTower, ShieldCheck } from 'lucide-react';
import { getCurrentUsername, isAdmin as isAdminUser } from '@/utils/auth';
interface PasswordForm {
  newUsername: string;
  currentPassword: string;
  newPassword: string;
  confirmPassword: string;
}


interface MenuItem {
  path: string;
  label: string;
  icon: React.ReactNode;
  color: string;
  description: string;
}

export default function ProfilePage() {
  const navigate = useNavigate();
  const { isOpen, onOpen, onOpenChange } = useDisclosure();
  const [username, setUsername] = useState('');
  const [isAdmin, setIsAdmin] = useState(false);
  const [passwordLoading, setPasswordLoading] = useState(false);
  const [passwordForm, setPasswordForm] = useState<PasswordForm>({
    newUsername: '',
    currentPassword: '',
    newPassword: '',
    confirmPassword: ''
  });

  useEffect(() => {
    setUsername(getCurrentUsername() || localStorage.getItem('name') || 'Admin');
    setIsAdmin(isAdminUser());
  }, []);

  // 管理员菜单项
  const adminMenuItems: MenuItem[] = [
    {
      path: '/port-resources',
      label: '资源中心',
      icon: <Boxes className="h-5 w-5" />,
      color: 'bg-emerald-100 dark:bg-emerald-500/20 text-emerald-600 dark:text-emerald-400',
      description: '管理端口、家庭设备、域名与动态解析'
    },
    {
      path: '/cross-entry-failover',
      label: '入口容灾',
      icon: <ShieldCheck className="h-5 w-5" />,
      color: 'bg-sky-100 dark:bg-sky-500/20 text-sky-600 dark:text-sky-400',
      description: '管理跨入口自动切换'
    },
    {
      path: '/source-ip-entry',
      label: '来源 IP 分流',
      icon: <RadioTower className="h-5 w-5" />,
      color: 'bg-cyan-100 dark:bg-cyan-500/20 text-cyan-600 dark:text-cyan-400',
      description: '按客户端真实来源 IP 选择入口线路'
    },
    {
      path: '/user',
      label: '用户管理',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path d="M9 6a3 3 0 11-6 0 3 3 0 016 0zM17 6a3 3 0 11-6 0 3 3 0 016 0zM12.93 17c.046-.327.07-.66.07-1a6.97 6.97 0 00-1.5-4.33A5 5 0 0119 16v1h-6.07zM6 11a5 5 0 015 5v1H1v-1a5 5 0 015-5z" />
        </svg>
      ),
      color: 'bg-blue-100 dark:bg-blue-500/20 text-blue-600 dark:text-blue-400',
      description: '管理系统用户'
    },
    {
      path: '/config',
      label: '网站配置',
      icon: (
        <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
          <path fillRule="evenodd" d="M11.49 3.17c-.38-1.56-2.6-1.56-2.98 0a1.532 1.532 0 01-2.286.948c-1.372-.836-2.942.734-2.106 2.106.54.886.061 2.042-.947 2.287-1.561.379-1.561 2.6 0 2.978a1.532 1.532 0 01.947 2.287c-.836 1.372.734 2.942 2.106 2.106a1.532 1.532 0 012.287.947c.379 1.561 2.6 1.561 2.978 0a1.533 1.533 0 012.287-.947c1.372.836 2.942-.734 2.106-2.106a1.533 1.533 0 01.947-2.287c1.561-.379 1.561-2.6 0-2.978a1.532 1.532 0 01-.947-2.287c.836-1.372-.734-2.942-2.106-2.106a1.532 1.532 0 01-2.287-.947zM10 13a3 3 0 100-6 3 3 0 000 6z" clipRule="evenodd" />
        </svg>
      ),
      color: 'bg-purple-100 dark:bg-purple-500/20 text-purple-600 dark:text-purple-400',
      description: '配置网站设置'
    }
  ];

  // 退出登录
  const handleLogout = () => {
    safeLogout();
    navigate('/', { replace: true });
  };

  // 密码表单验证
  const validatePasswordForm = (): boolean => {
    if (!passwordForm.newUsername.trim()) {
      toast.error('请输入新用户名');
      return false;
    }
    if (passwordForm.newUsername.length < 3) {
      toast.error('用户名长度至少3位');
      return false;
    }
    if (!passwordForm.currentPassword) {
      toast.error('请输入当前密码');
      return false;
    }
    if (!passwordForm.newPassword) {
      toast.error('请输入新密码');
      return false;
    }
    if (passwordForm.newPassword.length < 6) {
      toast.error('新密码长度不能少于6位');
      return false;
    }
    if (passwordForm.newPassword !== passwordForm.confirmPassword) {
      toast.error('两次输入密码不一致');
      return false;
    }
    return true;
  };

  // 提交密码修改
  const handlePasswordSubmit = async () => {
    if (!validatePasswordForm()) return;

    setPasswordLoading(true);
    try {
      const response = await updatePassword(passwordForm);
      if (response.code === 0) {
        toast.success('密码修改成功，请重新登录');
        onOpenChange();
        handleLogout();
      } else {
        toast.error(response.msg || '密码修改失败');
      }
    } catch (error) {
      toast.error('修改密码时发生错误');
      console.error('修改密码错误:', error);
    } finally {
      setPasswordLoading(false);
    }
  };

  // 重置密码表单
  const resetPasswordForm = () => {
    setPasswordForm({
      newUsername: '',
      currentPassword: '',
      newPassword: '',
      confirmPassword: ''
    });
  };

  return (
    <div className="px-3 lg:px-6 py-8 flex flex-col h-full">

      <div className="space-y-6 flex-1">
        {/* 用户信息卡片 */}
        <Card className="border border-gray-200 dark:border-default-200 shadow-md hover:shadow-lg transition-shadow">
          <CardBody className="p-4">
            <div className="flex items-center space-x-4">
              <div className="w-12 h-12 bg-primary-100 dark:bg-primary-900/30 rounded-full flex items-center justify-center">
                <svg className="w-6 h-6 text-primary" fill="currentColor" viewBox="0 0 20 20">
                  <path fillRule="evenodd" d="M10 9a3 3 0 100-6 3 3 0 000 6zm-7 9a7 7 0 1114 0H3z" clipRule="evenodd" />
                </svg>
              </div>
              <div className="flex-1">
                <h3 className="text-base font-medium text-foreground">{username}</h3>
                <div className="flex items-center space-x-2 mt-1">
                  <span className={`px-2 py-1 rounded-md text-xs font-medium ${
                    isAdmin 
                      ? 'bg-primary-100 dark:bg-primary-500/20 text-primary-700 dark:text-primary-300' 
                      : 'bg-blue-100 dark:bg-blue-500/20 text-blue-700 dark:text-blue-300'
                  }`}>
                    {isAdmin ? '管理员' : '普通用户'}
                  </span>
                  <span className="text-xs text-default-500">
                    {new Date().toLocaleDateString('zh-CN')}
                  </span>
                </div>
              </div>
            </div>
          </CardBody>
        </Card>

        {/* 功能网格 */}
        <Card className="border border-gray-200 dark:border-default-200 shadow-md hover:shadow-lg transition-shadow">
          <CardBody className="p-4">
            <div className="grid grid-cols-3 gap-3">
              <button
                onClick={() => navigate('/service-publishing')}
                className="flex flex-col items-center p-3 rounded-2xl bg-gray-50 dark:bg-default-100 hover:bg-gray-100 dark:hover:bg-default-200 transition-colors duration-200"
              >
                <div className="w-10 h-10 bg-cyan-100 dark:bg-cyan-500/20 text-cyan-600 dark:text-cyan-400 rounded-full flex items-center justify-center mb-2">
                  <RadioTower className="h-5 w-5" />
                </div>
                <span className="text-xs text-foreground text-center">内网映射</span>
              </button>
              <button
                onClick={() => navigate('/guide')}
                className="flex flex-col items-center p-3 rounded-2xl bg-gray-50 dark:bg-default-100 hover:bg-gray-100 dark:hover:bg-default-200 transition-colors duration-200"
              >
                <div className="w-10 h-10 bg-indigo-100 dark:bg-indigo-500/20 text-indigo-600 dark:text-indigo-400 rounded-full flex items-center justify-center mb-2">
                  <BookOpen className="h-5 w-5" />
                </div>
                <span className="text-xs text-foreground text-center">使用教程</span>
              </button>
              {/* 管理员功能 */}
              {isAdmin && adminMenuItems.map((item) => (
                <button
                  key={item.path}
                  onClick={() => navigate(item.path)}
                  className="flex flex-col items-center p-3 rounded-2xl bg-gray-50 dark:bg-default-100 hover:bg-gray-100 dark:hover:bg-default-200 transition-colors duration-200"
                >
                  <div className={`w-10 h-10 ${item.color} rounded-full flex items-center justify-center mb-2`}>
                    {item.icon}
                  </div>
                  <span className="text-xs text-foreground text-center">{item.label}</span>
                </button>
              ))}
              
              {/* 修改密码 */}
              <button
                onClick={onOpen}
                className="flex flex-col items-center p-3 rounded-2xl bg-gray-50 dark:bg-default-100 hover:bg-gray-100 dark:hover:bg-default-200 transition-colors duration-200"
              >
                <div className="w-10 h-10 bg-blue-100 dark:bg-blue-500/20 text-blue-600 dark:text-blue-400 rounded-full flex items-center justify-center mb-2">
                  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M18 8a6 6 0 01-7.743 5.743L10 14l-1 1-1 1H6v2H2v-4l4.257-4.257A6 6 0 1118 8zm-6-4a1 1 0 100 2 2 2 0 012 2 1 1 0 102 0 4 4 0 00-4-4z" clipRule="evenodd" />
                  </svg>
                </div>
                <span className="text-xs text-foreground text-center">修改密码</span>
              </button>
              
              {/* 退出登录 */}
              <button
                onClick={handleLogout}
                className="flex flex-col items-center p-3 rounded-2xl bg-gray-50 dark:bg-default-100 hover:bg-gray-100 dark:hover:bg-default-200 transition-colors duration-200"
              >
                <div className="w-10 h-10 bg-red-100 dark:bg-red-500/20 text-red-600 dark:text-red-400 rounded-full flex items-center justify-center mb-2">
                  <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 20 20">
                    <path fillRule="evenodd" d="M3 3a1 1 0 00-1 1v12a1 1 0 102 0V4a1 1 0 00-1-1zm10.293 9.293a1 1 0 001.414 1.414l3-3a1 1 0 000-1.414l-3-3a1 1 0 10-1.414 1.414L14.586 9H7a1 1 0 100 2h7.586l-1.293 1.293z" clipRule="evenodd" />
                  </svg>
                </div>
                <span className="text-xs text-foreground text-center">退出登录</span>
              </button>
            </div>
          </CardBody>
        </Card>

        <div className="fixed inset-x-0 bottom-20 text-center py-4">
               <p className="text-xs text-gray-400 dark:text-gray-500">
                 Powered by{' '}
                 <a 
                   href="https://github.com/NorwayXZ/flux-panel"
                   target="_blank" 
                   rel="noopener noreferrer"
                   className="text-gray-500 dark:text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 transition-colors"
                 >
                   WhySoQuiet
                 </a>
               </p>
               <p className="text-xs text-gray-400 dark:text-gray-500 mt-1">
                 v{ isWebViewFunc() ? siteConfig.app_version : siteConfig.version}
               </p>
             </div>

      </div>


      


      {/* 修改密码弹窗 */}
      <Modal 
        isOpen={isOpen} 
        onOpenChange={() => {
          onOpenChange();
          resetPasswordForm();
        }}
        size="2xl"
      scrollBehavior="outside"
      backdrop="blur"
      placement="center"
      >
        <ModalContent>
          {(onClose: () => void) => (
            <>
              <ModalHeader className="flex flex-col gap-1">修改密码</ModalHeader>
              <ModalBody>
                <div className="space-y-4">
                  <Input
                    label="新用户名"
                    placeholder="请输入新用户名（至少3位）"
                    value={passwordForm.newUsername}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newUsername: e.target.value }))}
                    variant="bordered"
                  />
                  <Input
                    label="当前密码"
                    type="password"
                    placeholder="请输入当前密码"
                    value={passwordForm.currentPassword}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, currentPassword: e.target.value }))}
                    variant="bordered"
                  />
                  <Input
                    label="新密码"
                    type="password"
                    placeholder="请输入新密码（至少6位）"
                    value={passwordForm.newPassword}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, newPassword: e.target.value }))}
                    variant="bordered"
                  />
                  <Input
                    label="确认密码"
                    type="password"
                    placeholder="请再次输入新密码"
                    value={passwordForm.confirmPassword}
                    onChange={(e: React.ChangeEvent<HTMLInputElement>) => setPasswordForm(prev => ({ ...prev, confirmPassword: e.target.value }))}
                    variant="bordered"
                  />
                </div>
              </ModalBody>
              <ModalFooter>
                <Button color="default" variant="light" onPress={onClose}>
                  取消
                </Button>
                <Button 
                  color="primary" 
                  onPress={handlePasswordSubmit}
                  isLoading={passwordLoading}
                >
                  确定
                </Button>
              </ModalFooter>
            </>
          )}
        </ModalContent>
      </Modal>
    </div>
  );
}
