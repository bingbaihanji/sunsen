package com.bingbaihanji.sunsen.core.version;

/**
 * 语义化版本号记录类,遵循 SemVer 2.0 规范.
 * <p>
 * 支持 MAJOR.MINOR.PATCH[-preRelease][+build] 格式,
 * 预发布版本在比较时低于同版本的正式版(如 1.0.0-beta &lt; 1.0.0).
 *
 * @param major      主版本号
 * @param minor      次版本号
 * @param patch      修订版本号
 * @param preRelease 预发布标识符(如 "beta","alpha.1"),无预发布段时为 null
 * @param build      构建元数据,比较时忽略
 * @author 冰白寒祭
 * @date 2025
 */
public record SemVer(
        int major,
        int minor,
        int patch,
        String preRelease,
        String build
) implements Comparable<SemVer> {

    /**
     * 紧凑构造器,仅提供主版本号的便捷构造器.
     */
    public SemVer(int major, int minor, int patch) {
        this(major, minor, patch, null, null);
    }

    /**
     * 解析语义化版本字符串
     *
     * @param version 版本字符串,如 "1.2.3","v1.0.0-beta.1","1.2.3+build123"
     * @return 解析后的 SemVer 对象
     * @throws IllegalArgumentException 版本格式不合法
     */
    public static SemVer parse(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank");
        }
        String v = version.trim();
        // 去除可能的前缀 'v' 或 'V'
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        // 提取构建元数据(比较时忽略)
        String build = null;
        int plusIdx = v.indexOf('+');
        if (plusIdx >= 0) {
            build = v.substring(plusIdx + 1);
            v = v.substring(0, plusIdx);
        }
        // 提取预发布标识符
        String preRelease = null;
        int hyphenIdx = v.indexOf('-');
        if (hyphenIdx >= 0) {
            preRelease = v.substring(hyphenIdx + 1);
            v = v.substring(0, hyphenIdx);
        }
        String[] parts = v.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid SemVer format: " + version);
        }
        if (parts.length > 3) {
            throw new IllegalArgumentException(
                    "Invalid SemVer format (too many segments): " + version);
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return new SemVer(major, minor, patch, preRelease, build);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SemVer format: " + version, e);
        }
    }

    /**
     * 判断是否为预发布版本.
     */
    public boolean isPreRelease() {
        return preRelease != null && !preRelease.isBlank();
    }

    /**
     * 按主,次,修订版本号依次比较,预发布版低于正式版.
     *
     * @param other 另一个 SemVer 对象
     * @return 负数表示小于,0 表示等于,正数表示大于
     */
    @Override
    public int compareTo(SemVer other) {
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) {
            return cmp;
        }
        cmp = Integer.compare(this.patch, other.patch);
        if (cmp != 0) {
            return cmp;
        }
        // 预发布版本低于对应的正式版本
        if (this.isPreRelease() && !other.isPreRelease()) {
            return -1;
        }
        if (!this.isPreRelease() && other.isPreRelease()) {
            return 1;
        }
        if (this.isPreRelease()) {
            return this.preRelease.compareTo(other.preRelease);
        }
        return 0;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(major).append('.').append(minor).append('.').append(patch);
        if (isPreRelease()) {
            sb.append('-').append(preRelease);
        }
        if (build != null && !build.isBlank()) {
            sb.append('+').append(build);
        }
        return sb.toString();
    }
}
