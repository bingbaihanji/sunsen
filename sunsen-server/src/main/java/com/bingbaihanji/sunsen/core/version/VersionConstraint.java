package com.bingbaihanji.sunsen.core.version;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 版本约束表达式解析器
 * 支持以下格式:
 * <ul>
 *   <li>范围:{@code >=1.0.0 <2.0.0}</li>
 *   <li>兼容版本:{@code ^1.2.0}(等价于 {@code >=1.2.0 <2.0.0})</li>
 *   <li>近似版本:{@code ~1.2.0}(等价于 {@code >=1.2.0 <1.3.0})</li>
 *   <li>精确版本:{@code 1.2.0}(等价于 {@code =1.2.0})</li>
 * </ul>
 */
public final class VersionConstraint {

    // 判断字符串是否包含操作符的正则
    private static final Pattern OPERATOR_PATTERN = Pattern.compile(".*[<>=!].*");

    // 版本约束谓词列表,所有谓词必须同时满足
    private final List<Predicate> predicates;

    private VersionConstraint(List<Predicate> predicates) {
        this.predicates = List.copyOf(predicates);
    }

    /**
     * 解析版本约束表达式
     *
     * @param expression 约束表达式,如 ">=1.0.0 <2.0.0","^1.2.0","~1.2.0"
     * @return 解析后的版本约束对象
     */
    public static VersionConstraint parse(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("version constraint must not be null or blank");
        }
        String expr = expression.trim();
        List<Predicate> predicates = new ArrayList<>();

        // 处理 ^ 和 ~ 前缀
        if (expr.startsWith("^")) {
            SemVer base = SemVer.parse(expr.substring(1).trim());
            predicates.add(new Predicate(Operator.GTE, base));
            predicates.add(new Predicate(Operator.LT, new SemVer(base.major() + 1, 0, 0)));
            return new VersionConstraint(predicates);
        }
        if (expr.startsWith("~")) {
            SemVer base = SemVer.parse(expr.substring(1).trim());
            predicates.add(new Predicate(Operator.GTE, base));
            predicates.add(new Predicate(Operator.LT, new SemVer(base.major(), base.minor() + 1, 0)));
            return new VersionConstraint(predicates);
        }

        // 处理精确版本(无操作符)
        if (!OPERATOR_PATTERN.matcher(expr).matches()) {
            SemVer exact = SemVer.parse(expr);
            predicates.add(new Predicate(Operator.EQ, exact));
            return new VersionConstraint(predicates);
        }

        // 处理范围表达式,按空格拆分多个条件
        String[] parts = expr.split("\\s+");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            predicates.add(parsePredicate(part));
        }
        return new VersionConstraint(predicates);
    }

    private static Predicate parsePredicate(String part) {
        Operator op;
        String versionStr;
        if (part.startsWith(">=")) {
            op = Operator.GTE;
            versionStr = part.substring(2);
        } else if (part.startsWith("<=")) {
            op = Operator.LTE;
            versionStr = part.substring(2);
        } else if (part.startsWith(">")) {
            op = Operator.GT;
            versionStr = part.substring(1);
        } else if (part.startsWith("<")) {
            op = Operator.LT;
            versionStr = part.substring(1);
        } else if (part.startsWith("!=")) {
            op = Operator.NEQ;
            versionStr = part.substring(2);
        } else if (part.startsWith("=")) {
            op = Operator.EQ;
            versionStr = part.substring(1);
        } else {
            throw new IllegalArgumentException("Unknown version constraint operator in: " + part);
        }
        SemVer version = SemVer.parse(versionStr.trim());
        return new Predicate(op, version);
    }

    /**
     * 判断给定版本是否满足本约束
     *
     * @param version 待校验版本
     * @return true 表示满足约束
     */
    public boolean matches(SemVer version) {
        for (Predicate predicate : predicates) {
            if (!predicate.test(version)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断给定版本字符串是否满足本约束
     *
     * @param version 待校验版本字符串
     * @return true 表示满足约束
     */
    public boolean matches(String version) {
        return matches(SemVer.parse(version));
    }

    @Override
    public String toString() {
        return predicates.toString();
    }

    // 版本比较操作符
    private enum Operator {
        // 等于
        EQ,
        // 不等于
        NEQ,
        // 大于
        GT,
        // 小于
        LT,
        // 大于等于
        GTE,
        // 小于等于
        LTE
    }

    /**
     * 版本约束谓词
     *
     * @param op      比较操作符
     * @param version 目标版本
     */
    private record Predicate(Operator op, SemVer version) {
        /**
         * 测试给定版本是否满足本谓词
         */
        boolean test(SemVer v) {
            int cmp = v.compareTo(version);
            return switch (op) {
                case EQ -> cmp == 0;
                case NEQ -> cmp != 0;
                case GT -> cmp > 0;
                case LT -> cmp < 0;
                case GTE -> cmp >= 0;
                case LTE -> cmp <= 0;
            };
        }
    }
}
