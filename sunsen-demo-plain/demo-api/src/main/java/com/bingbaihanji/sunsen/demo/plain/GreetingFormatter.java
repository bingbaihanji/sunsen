package com.bingbaihanji.sunsen.demo.plain;

import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

/**
 * 问候语格式化扩展点(同一插件内仅允许一个实现)
 * <p>
 * {@code allowMultiple = false} 限制：同一个插件内最多有一个 {@link GreetingFormatter} 实现，
 * 多个插件之间互不干涉。宿主仍可拿到所有实现的列表，按 {@code @Extension.order} 升序排列。
 */
@ExtensionPoint(id = "greeting-formatter", description = "问候语格式化器", allowMultiple = false)
public interface GreetingFormatter {
    /**
     * 包装问候语文本(添加装饰、前后缀等)
     */
    String format(String greeting);
}
