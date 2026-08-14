export interface PortForwardOption {
  id: number;
  name: string;
  inPort: number;
  nodeName?: string;
  entryHost?: string;
}

export interface PortForwardOptionGroup<T extends PortForwardOption> {
  port: number;
  options: T[];
}

export function groupForwardOptionsByPort<T extends PortForwardOption>(
  options: T[],
): PortForwardOptionGroup<T>[] {
  const groups = new Map<number, T[]>();

  options.forEach((option) => {
    const group = groups.get(option.inPort) || [];

    group.push(option);
    groups.set(option.inPort, group);
  });

  return Array.from(groups.entries())
    .map(([port, groupedOptions]) => ({
      port,
      options: [...groupedOptions].sort((left, right) => {
        const nodeOrder = (left.nodeName || "").localeCompare(
          right.nodeName || "",
          "zh-CN",
        );

        if (nodeOrder !== 0) return nodeOrder;
        const nameOrder = left.name.localeCompare(right.name, "zh-CN");

        return nameOrder !== 0 ? nameOrder : left.id - right.id;
      }),
    }))
    .sort((left, right) => left.port - right.port);
}
