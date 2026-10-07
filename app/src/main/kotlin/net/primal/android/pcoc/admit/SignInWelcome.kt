package net.primal.android.pcoc.admit

/**
 * First open of Sign in shows the how-to. Don't show again skips later opens.
 * Continuing enters the camera and paste screen either way.
 */
class SignInWelcomeGate(skipWelcome: Boolean = false) {
    var skipWelcome: Boolean = skipWelcome
        private set

    fun onOpen(): SignInWelcomeStep = if (skipWelcome) SignInWelcomeStep.Camera else SignInWelcomeStep.Welcome

    fun setDontShowAgain(checked: Boolean) {
        skipWelcome = checked
    }

    fun enterSignIn(): SignInWelcomeStep = SignInWelcomeStep.Camera
}

enum class SignInWelcomeStep {
    Welcome,
    Camera,
}
