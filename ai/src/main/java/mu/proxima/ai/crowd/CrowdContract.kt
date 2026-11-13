package mu.proxima.ai.crowd

// #30: the contract now lives in core/ (model/Domain.kt + engine/Engines.kt).
// These aliases keep existing imports compiling; switch to mu.proxima.core.* and they can be deleted.

@Deprecated("Moved to core (#30)", ReplaceWith("mu.proxima.core.model.CrowdRiskLevel"))
typealias CrowdRiskLevel = mu.proxima.core.model.CrowdRiskLevel

@Deprecated("Moved to core (#30)", ReplaceWith("mu.proxima.core.model.CrowdRisk"))
typealias CrowdRisk = mu.proxima.core.model.CrowdRisk

@Deprecated("Moved to core (#30)", ReplaceWith("mu.proxima.core.engine.CrowdRiskEngine"))
typealias CrowdRiskEngine = mu.proxima.core.engine.CrowdRiskEngine
