package net.primal.android.core.compose

/**
 * The five controls on the main bar. Center is Sign in.
 * Wallet can still open from elsewhere. It is not this slot, and the bolt is not drawn here.
 */
val mainBarDestinations: List<PrimalTopLevelDestination> = listOf(
    PrimalTopLevelDestination.Feeds,
    PrimalTopLevelDestination.Reads,
    PrimalTopLevelDestination.SignIn,
    PrimalTopLevelDestination.Alerts,
    PrimalTopLevelDestination.Explore,
)
