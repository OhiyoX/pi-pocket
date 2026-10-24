package id.harness.pipocket.ui

import id.harness.pipocket.ModelRef

/**
 * 模型选择器的纯逻辑（搜索 + "当前"判定）。
 *
 * 为什么单独抽出来：
 *  · 模型列表动辄几十上百条（这台机器上是 100+），**没有搜索基本不可用**；
 *  · "当前"那枚标记以前只比 model id，但同一个 id 常属于多个 provider
 *    （deepseek-v4.1-flash 在好几家都有) ⇒ 会给好几种行同时打"当前"。判定必须带 provider。
 */

/**
 * 按查询词过滤模型。
 *
 * 规则故意简单但够用：
 *  · 用空格分词，**每个词都要命中**（"deep seek" 也能搜到 `deepseek`）；
 *  · 命中范围 = `provider/model`（这样既能按厂商筛，也能按型号筛）；
 *  · 忽略大小写与首尾空白；空查询 = 全部。
 */
fun filterModels(models: List<ModelRef>, query: String): List<ModelRef> {
    val tokens = query.trim().lowercase().split(' ', '\u3000').filter { it.isNotBlank() }
    if (tokens.isEmpty()) return models
    return models.filter { ref ->
        val haystack = (ref.provider + "/" + ref.model).lowercase()
        tokens.all { haystack.contains(it) }
    }
}

/**
 * 这一行是不是"当前模型"。
 *
 * @param currentProvider 空 = 不知道 provider（老桥/未连上），那就退化成只比 id（和以前一样）
 */
fun isCurrentModel(
    ref: ModelRef,
    currentModel: String,
    currentProvider: String,
): Boolean {
    if (ref.model != currentModel) return false
    if (currentProvider.isBlank()) return true
    return ref.provider == currentProvider
}

/**
 * 搜索结果为空时的提示（区分"一条都没有"和"没搜到"）。
 */
fun modelSearchEmptyHint(total: Int, query: String): String = when {
    total == 0 -> "正在读取模型列表…"
    query.isBlank() -> "没有可用模型"
    else -> "没有匹配「${query.trim()}」的模型"
}

/**
 * 快切（‹ ›）在两个按钮里给出"相邻模型"。
 *
 * 用户 2026-10-23：切换模型要有确认按钮 ⇒ 快切也**不再立刻生效**，
 * 只是把相邻那条填成待切换项，由底部「确认切换」统一落地。
 * 当前模型不在列表里（列表没拉到/被删了）时，退回第一条/最后一条。
 */
fun neighborModel(
    models: List<ModelRef>,
    currentModel: String,
    currentProvider: String,
    backward: Boolean,
): ModelRef? {
    if (models.isEmpty()) return null
    val index = models.indexOfFirst { isCurrentModel(it, currentModel, currentProvider) }
    if (index < 0) return if (backward) models.last() else models.first()
    val step = if (backward) -1 else 1
    return models[(index + step + models.size) % models.size]
}

/** 两个模型引用是不是同一条（provider + model 都比）。 */
fun sameModel(a: ModelRef?, b: ModelRef?): Boolean =
    a != null && b != null && a.provider == b.provider && a.model == b.model
