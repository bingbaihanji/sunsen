package com.bingbaihanji.sunsen.api;

import java.util.Objects;

/**
 * 插件依赖描述记录类
 *
 * @param id                依赖插件的唯一标识符
 * @param versionConstraint 依赖版本约束表达式
 * @param optional          是否为可选依赖
 */
public record DependencyDescriptor(String id, String versionConstraint, boolean optional) {

    public DependencyDescriptor(String id, String versionConstraint, boolean optional) {
        this.id = Objects.requireNonNull(id, "id must not be null");
        this.versionConstraint = Objects.requireNonNull(versionConstraint, "versionConstraint must not be null");
        this.optional = optional;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DependencyDescriptor that = (DependencyDescriptor) o;
        return optional == that.optional && id.equals(that.id) && versionConstraint.equals(that.versionConstraint);
    }

    @Override
    public String toString() {
        return "DependencyDescriptor{" +
                "id='" + id + '\'' +
                ", versionConstraint='" + versionConstraint + '\'' +
                ", optional=" + optional +
                '}';
    }
}
