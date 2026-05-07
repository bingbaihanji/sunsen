package com.bingbaihanji.sunsen.core.version;

/**
 * 语义化版本号记录类,格式为 MAJOR.MINOR.PATCH
 *
 * @param major 主版本号
 * @param minor 次版本号
 * @param patch 修订版本号
 */
public record SemVer(
        int major,
        int minor,
        int patch
) implements Comparable<SemVer> {

    /**
     * @param major 主版本号
     * @param minor 次版本号
     * @param patch 修订版本号
     */
    public SemVer {
    }

    /**
     * 解析语义化版本字符串
     *
     * @param version 版本字符串,如 "1.2.3" 或 "v1.0.0"
     * @return 解析后的 SemVer 对象
     */
    public static SemVer parse(String version) {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("version must not be null or blank");
        }
        // 去除可能的前缀 'v'
        String v = version.startsWith("v") || version.startsWith("V") ? version.substring(1) : version;
        // 只取主版本号部分(忽略预发布版本和构建元数据)
        int plusIdx = v.indexOf('+');
        if (plusIdx >= 0) {
            v = v.substring(0, plusIdx);
        }
        int hyphenIdx = v.indexOf('-');
        if (hyphenIdx >= 0) {
            v = v.substring(0, hyphenIdx);
        }
        String[] parts = v.split("\\.");
        if (parts.length < 2) {
            throw new IllegalArgumentException("Invalid SemVer format: " + version);
        }
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = parts.length >= 3 ? Integer.parseInt(parts[2]) : 0;
            return new SemVer(major, minor, patch);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid SemVer format: " + version, e);
        }
    }

    /**
     * @return 主版本号
     */
    @Override
    public int major() {
        return major;
    }

    /**
     * @return 次版本号
     */
    @Override
    public int minor() {
        return minor;
    }

    /**
     * @return 修订版本号
     */
    @Override
    public int patch() {
        return patch;
    }

    /**
     * 按主,次,修订版本号依次比较
     *
     * @param other 另一个 SemVer 对象
     * @return 负数表示小于,0 表示等于,正数表示大于
     */
    @Override
    public int compareTo(SemVer other) {
        int cmp = Integer.compare(this.major, other.major);
        if (cmp != 0) return cmp;
        cmp = Integer.compare(this.minor, other.minor);
        if (cmp != 0) return cmp;
        return Integer.compare(this.patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
