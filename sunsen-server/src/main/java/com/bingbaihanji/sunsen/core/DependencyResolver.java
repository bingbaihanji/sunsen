package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.CircularDependencyException;
import com.bingbaihanji.sunsen.api.DependencyDescriptor;
import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.core.version.SemVer;
import com.bingbaihanji.sunsen.core.version.VersionConstraint;

import java.util.*;

/**
 * 插件依赖解析器,负责依赖存在性校验,版本约束校验,循环依赖检测与拓扑排序
 * <p>
 * 加载插件前必须先调用 {@link #resolve(List)} 完成校验,再通过 {@link #sort(List)} 获取安全的启动顺序
 */
public class DependencyResolver {

    /**
     * 构建插件 ID -> 描述符的映射表,检测重复 ID
     */
    private static Map<String, PluginDescriptor> buildIdMap(List<PluginDescriptor> all) {
        Map<String, PluginDescriptor> idMap = new HashMap<>(all.size() * 2);
        for (PluginDescriptor descriptor : all) {
            PluginDescriptor existing = idMap.put(descriptor.id(), descriptor);
            if (existing != null) {
                throw new PluginLoadException(
                        "Duplicate plugin ID: '" + descriptor.id() + "' found in multiple JARs"
                );
            }
        }
        return idMap;
    }

    /**
     * 构建正向邻接表(descriptor -> dep),同时填充入度表(供拓扑排序使用)
     * 返回的是反向邻接表(dep -> descriptor),即 target 先于 descriptor 启动
     */
    private static Map<String, List<String>> buildRevAdj(List<PluginDescriptor> all,
                                                         Map<String, PluginDescriptor> idMap,
                                                         Map<String, Integer> inDegree) {
        Map<String, List<String>> revAdj = new HashMap<>();
        for (PluginDescriptor d : all) {
            inDegree.put(d.id(), 0);
            revAdj.put(d.id(), new ArrayList<>());
        }
        for (PluginDescriptor d : all) {
            for (DependencyDescriptor dep : d.dependencies()) {
                if (!idMap.containsKey(dep.id())) continue; // optional 缺失
                revAdj.get(dep.id()).add(d.id());
                inDegree.merge(d.id(), 1, Integer::sum);
            }
        }
        return revAdj;
    }

    /**
     * 构建正向邻接表(descriptor -> dep)用于 DFS 循环检测
     */
    private static Map<String, List<String>> buildAdj(List<PluginDescriptor> all,
                                                      Map<String, PluginDescriptor> idMap) {
        Map<String, List<String>> adj = new HashMap<>();
        for (PluginDescriptor d : all) {
            adj.put(d.id(), new ArrayList<>());
        }
        for (PluginDescriptor d : all) {
            for (DependencyDescriptor dep : d.dependencies()) {
                if (idMap.containsKey(dep.id())) {
                    adj.get(d.id()).add(dep.id());
                }
            }
        }
        return adj;
    }

    /**
     * 对给定插件列表执行依赖校验
     *
     * @throws PluginLoadException         依赖缺失或版本不匹配
     * @throws CircularDependencyException 发现循环依赖
     */
    public void resolve(List<PluginDescriptor> all) {
        Map<String, PluginDescriptor> idMap = buildIdMap(all);

        // 步骤一:存在性校验
        for (PluginDescriptor descriptor : all) {
            for (DependencyDescriptor dep : descriptor.dependencies()) {
                if (!dep.optional() && !idMap.containsKey(dep.id())) {
                    throw new PluginLoadException(
                            "插件 " + descriptor.id() + " 依赖的插件 " + dep.id() + " 不存在"
                    );
                }
            }
        }

        // 步骤二:版本约束校验
        for (PluginDescriptor descriptor : all) {
            for (DependencyDescriptor dep : descriptor.dependencies()) {
                PluginDescriptor target = idMap.get(dep.id());
                if (target == null) continue; // optional 缺失已在上一步放过
                VersionConstraint constraint = VersionConstraint.parse(dep.versionConstraint());
                SemVer targetVersion = SemVer.parse(target.version());
                if (!constraint.matches(targetVersion)) {
                    throw new PluginLoadException(
                            "插件 " + descriptor.id() + " 依赖 " + dep.id()
                                    + " 的版本约束 " + dep.versionConstraint()
                                    + " 不满足实际版本 " + target.version()
                    );
                }
            }
        }

        // 步骤三:循环依赖检测(DFS)
        detectCycles(all, idMap);
    }

    /**
     * 对给定插件列表执行拓扑排序(Kahn 算法)
     *
     * @return 按依赖顺序排列的插件列表(依赖在前)
     */
    public List<PluginDescriptor> sort(List<PluginDescriptor> all) {
        Map<String, PluginDescriptor> idMap = buildIdMap(all);

        Map<String, Integer> inDegree = new HashMap<>();
        // adj 方向:target -> descriptor(target 先启动),由 buildRevAdj 构建
        Map<String, List<String>> adj = buildRevAdj(all, idMap, inDegree);

        Queue<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<PluginDescriptor> result = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            result.add(idMap.get(id));
            for (String next : adj.get(id)) {
                int newDegree = inDegree.get(next) - 1;
                inDegree.put(next, newDegree);
                if (newDegree == 0) {
                    queue.add(next);
                }
            }
        }

        if (result.size() != all.size()) {
            // 正常情况下循环依赖已由 resolve() 中的 detectCycles() 提前拦截;
            // 若单独调用 sort(),此处给出明确错误信息
            Set<String> sorted = new HashSet<>();
            for (PluginDescriptor d : result) sorted.add(d.id());
            List<String> remaining = new ArrayList<>();
            for (PluginDescriptor d : all) {
                if (!sorted.contains(d.id())) remaining.add(d.id());
            }
            throw new CircularDependencyException(remaining);
        }
        return result;
    }

    /**
     * 对给定插件列表执行分层拓扑排序,同一层内的插件互相无依赖关系,可并行加载.
     * <p>
     * 与 {@link #sort(List)} 使用相同的 Kahn BFS 算法,区别在于每一轮同时处理所有零入度节点,
     * 将它们作为一个波次(wave)输出,然后再推进到下一波次.
     *
     * @return 按依赖顺序排列的波次列表;同一波次内的插件可安全地并行加载
     */
    public List<List<PluginDescriptor>> sortByLevels(List<PluginDescriptor> all) {
        Map<String, PluginDescriptor> idMap = buildIdMap(all);
        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adj = buildRevAdj(all, idMap, inDegree);

        List<String> zeroQueue = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) zeroQueue.add(entry.getKey());
        }

        List<List<PluginDescriptor>> levels = new ArrayList<>();
        int processed = 0;
        while (!zeroQueue.isEmpty()) {
            List<PluginDescriptor> wave = new ArrayList<>(zeroQueue.size());
            List<String> nextQueue = new ArrayList<>();
            for (String id : zeroQueue) {
                wave.add(idMap.get(id));
                for (String next : adj.get(id)) {
                    int newDegree = inDegree.get(next) - 1;
                    inDegree.put(next, newDegree);
                    if (newDegree == 0) nextQueue.add(next);
                }
            }
            processed += wave.size();
            levels.add(wave);
            zeroQueue = nextQueue;
        }

        if (processed != all.size()) {
            Set<String> sorted = new HashSet<>();
            for (List<PluginDescriptor> wave : levels) {
                for (PluginDescriptor d : wave) sorted.add(d.id());
            }
            List<String> remaining = new ArrayList<>();
            for (PluginDescriptor d : all) {
                if (!sorted.contains(d.id())) remaining.add(d.id());
            }
            throw new CircularDependencyException(remaining);
        }
        return levels;
    }

    /**
     * 使用 DFS 检测循环依赖
     */
    private void detectCycles(List<PluginDescriptor> all, Map<String, PluginDescriptor> idMap) {
        Map<String, List<String>> adj = buildAdj(all, idMap);

        Set<String> visited = new HashSet<>();
        Set<String> recStack = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (String id : adj.keySet()) {
            if (!visited.contains(id)) {
                if (dfs(id, adj, visited, recStack, path)) {
                    return; // 异常已在 dfs 中抛出
                }
            }
        }
    }

    /**
     * 深度优先搜索,发现环时立即抛出异常
     */
    private boolean dfs(String node,
                        Map<String, List<String>> adj,
                        Set<String> visited,
                        Set<String> recStack,
                        Deque<String> path) {
        visited.add(node);
        recStack.add(node);
        path.push(node);

        for (String neighbor : adj.getOrDefault(node, List.of())) {
            if (!visited.contains(neighbor)) {
                if (dfs(neighbor, adj, visited, recStack, path)) {
                    return true;
                }
            } else if (recStack.contains(neighbor)) {
                // 发现环,收集环上节点
                List<String> cycle = new ArrayList<>();
                boolean collect = false;
                for (String id : path.reversed()) {
                    if (id.equals(neighbor)) collect = true;
                    if (collect) cycle.add(id);
                }
                cycle.add(neighbor);
                throw new CircularDependencyException(cycle);
            }
        }

        path.pop();
        recStack.remove(node);
        return false;
    }
}
