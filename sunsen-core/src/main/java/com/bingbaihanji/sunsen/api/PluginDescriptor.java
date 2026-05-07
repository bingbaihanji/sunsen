package com.bingbaihanji.sunsen.api;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 插件元数据描述,对应 {@code META-INF/plugin.json} 的内存模型,不可变
 *
 * @param id              插件唯一标识符
 * @param name            插件显示名称
 * @param description     插件描述信息
 * @param version         插件版本号
 * @param apiVersion      框架 API 兼容版本
 * @param mainClass       插件主类全限定名
 * @param packagePrefixes 插件类所在的包前缀列表
 * @param dependencies    插件依赖列表
 * @param permissions     插件权限集合
 * @param vendorInfo      插件供应商信息
 */
public record PluginDescriptor(
        String id,
        String name,
        String description,
        String version,
        String apiVersion,
        String mainClass,
        List<String> packagePrefixes,
        List<DependencyDescriptor> dependencies,
        Set<String> permissions,
        Map<String, String> vendorInfo
) {

    /**
     * 构造插件描述符,所有集合参数会被复制为不可变集合
     *
     * @param id              插件唯一标识符
     * @param name            插件显示名称
     * @param description     插件描述信息
     * @param version         插件版本号
     * @param apiVersion      框架 API 兼容版本
     * @param mainClass       插件主类全限定名
     * @param packagePrefixes 插件类所在的包前缀列表
     * @param dependencies    插件依赖列表
     * @param permissions     插件权限集合
     * @param vendorInfo      插件供应商信息
     */
    public PluginDescriptor(String id,
                            String name,
                            String description,
                            String version,
                            String apiVersion,
                            String mainClass,
                            List<String> packagePrefixes,
                            List<DependencyDescriptor> dependencies,
                            Set<String> permissions,
                            Map<String, String> vendorInfo) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.name = Objects.requireNonNull(name, "name must not be null");
        this.description = description;
        this.version = Objects.requireNonNull(version, "version must not be null");
        this.apiVersion = Objects.requireNonNull(apiVersion, "apiVersion must not be null");
        this.mainClass = Objects.requireNonNull(mainClass, "mainClass must not be null");
        this.packagePrefixes = List.copyOf(Objects.requireNonNull(packagePrefixes, "packagePrefixes must not be null"));
        this.dependencies = List.copyOf(Objects.requireNonNull(dependencies, "dependencies must not be null"));
        this.permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions must not be null"));
        this.vendorInfo = Map.copyOf(Objects.requireNonNull(vendorInfo, "vendorInfo must not be null"));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PluginDescriptor that = (PluginDescriptor) o;
        return id.equals(that.id) && version.equals(that.version);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, version);
    }

    @Override
    public String toString() {
        return "PluginDescriptor{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", version='" + version + '\'' +
                '}';
    }
}
