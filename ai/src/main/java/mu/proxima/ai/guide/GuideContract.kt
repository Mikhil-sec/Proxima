package mu.proxima.ai.guide

// #30: the contract now lives in core/ (model/Domain.kt + engine/Engines.kt).
// These aliases keep existing imports compiling; switch to mu.proxima.core.* and they can be deleted.

@Deprecated("Moved to core (#30)", ReplaceWith("mu.proxima.core.model.GuideState"))
typealias GuideState = mu.proxima.core.model.GuideState

@Deprecated("Moved to core (#30)", ReplaceWith("mu.proxima.core.engine.GuideEngine"))
typealias GuideEngine = mu.proxima.core.engine.GuideEngine
