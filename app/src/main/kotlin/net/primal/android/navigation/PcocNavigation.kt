package net.primal.android.navigation

import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.navDeepLink
import androidx.navigation.navigation
import net.primal.android.core.compose.ApplyEdgeToEdge
import net.primal.android.core.compose.LockToOrientationPortrait
import net.primal.android.core.compose.runtime.DisposableLifecycleObserverEffect
import net.primal.android.pcoc.PcocAccountMarkViewModel
import net.primal.android.pcoc.PcocDoor
import net.primal.android.pcoc.PcocScreen
import net.primal.android.pcoc.admit.RequestAdmissionScreen
import net.primal.android.pcoc.admit.RequestAdmissionViewModel
import net.primal.android.pcoc.admit.SIGN_IN_APP_LINK
import net.primal.android.pcoc.create.CreatePcocScreen
import net.primal.android.pcoc.create.CreatePcocViewModel
import net.primal.android.pcoc.enroll.EnrollPcocScreen
import net.primal.android.pcoc.enroll.EnrollPcocViewModel
import net.primal.android.pcoc.verify.VerifyPcocScreen
import net.primal.android.pcoc.verify.VerifyPcocViewModel

fun NavGraphBuilder.pcocNavigation(navController: NavController) {
    navigation(route = PCOC_GRAPH, startDestination = PCOC_HOME) {
        composable(
            route = PCOC_HOME,
            enterTransition = { primalSlideInHorizontallyFromEnd },
            exitTransition = { primalScaleOut },
            popEnterTransition = { primalScaleIn },
            popExitTransition = { primalSlideOutHorizontallyToEnd },
        ) {
            ApplyEdgeToEdge()
            LockToOrientationPortrait()
            val markModel = hiltViewModel<PcocAccountMarkViewModel>()
            val saved by markModel.saved.collectAsState()
            val accountName by markModel.accountName.collectAsState()
            DisposableLifecycleObserverEffect(markModel) { event ->
                if (event == Lifecycle.Event.ON_START) markModel.refresh()
            }
            PcocScreen(
                onClose = { navController.navigateUp() },
                onDoor = { door -> navController.navigate(door.route()) },
                accountName = accountName,
                showPcocMark = saved,
            )
        }
        composable(
            route = PCOC_ENROLL,
            enterTransition = { primalSlideInHorizontallyFromEnd },
            exitTransition = { primalScaleOut },
            popEnterTransition = { primalScaleIn },
            popExitTransition = { primalSlideOutHorizontallyToEnd },
        ) {
            val viewModel = hiltViewModel<EnrollPcocViewModel>()
            ApplyEdgeToEdge()
            LockToOrientationPortrait()
            EnrollPcocScreen(viewModel = viewModel, onClose = { navController.navigateUp() })
        }
        composable(
            route = PCOC_VERIFY,
            enterTransition = { primalSlideInHorizontallyFromEnd },
            exitTransition = { primalScaleOut },
            popEnterTransition = { primalScaleIn },
            popExitTransition = { primalSlideOutHorizontallyToEnd },
        ) {
            val viewModel = hiltViewModel<VerifyPcocViewModel>()
            ApplyEdgeToEdge()
            LockToOrientationPortrait()
            VerifyPcocScreen(viewModel = viewModel, onClose = { navController.navigateUp() })
        }
        composable(
            route = PCOC_CREATE,
            enterTransition = { primalSlideInHorizontallyFromEnd },
            exitTransition = { primalScaleOut },
            popEnterTransition = { primalScaleIn },
            popExitTransition = { primalSlideOutHorizontallyToEnd },
        ) {
            val viewModel = hiltViewModel<CreatePcocViewModel>()
            ApplyEdgeToEdge()
            LockToOrientationPortrait()
            CreatePcocScreen(viewModel = viewModel, onClose = { navController.navigateUp() })
        }
    }
}

/** Sign in (camera and paste). A root route so Back returns to the screen that opened it. */
fun NavGraphBuilder.signInDestination(navController: NavController) {
    composable(
        route = PCOC_ADMIT,
        deepLinks = listOf(
            navDeepLink { uriPattern = SIGN_IN_APP_LINK },
        ),
        enterTransition = { primalSlideInHorizontallyFromEnd },
        exitTransition = { primalScaleOut },
        popEnterTransition = { primalScaleIn },
        popExitTransition = { primalSlideOutHorizontallyToEnd },
    ) {
        val viewModel = hiltViewModel<RequestAdmissionViewModel>()
        ApplyEdgeToEdge()
        LockToOrientationPortrait()
        RequestAdmissionScreen(
            viewModel = viewModel,
            onClose = { navController.navigateUp() },
            onCloseToMenu = { navController.closeSignInToPcocMenu() },
            appLink = it.savedStateHandle.get<Intent>(NavController.KEY_DEEP_LINK_INTENT)?.dataString,
        )
    }
}

fun NavController.navigateToSignIn() = navigate(route = PCOC_ADMIT)

/** Leaves Sign in for the PCOC menu, whether Sign in was opened from the menu or from the bottom bar. */
private fun NavController.closeSignInToPcocMenu() {
    if (!popBackStack(route = PCOC_HOME, inclusive = false)) {
        navigate(route = PCOC_GRAPH) {
            popUpTo(route = PCOC_ADMIT) { inclusive = true }
        }
    }
}

private fun PcocDoor.route(): String =
    when (this) {
        PcocDoor.Enroll -> PCOC_ENROLL
        PcocDoor.Create -> PCOC_CREATE
        PcocDoor.Verify -> PCOC_VERIFY
        PcocDoor.Admit -> PCOC_ADMIT
    }

private const val PCOC_GRAPH = "pcoc"
private const val PCOC_HOME = "pcoc_home"
private const val PCOC_ENROLL = "pcoc_enroll"
private const val PCOC_VERIFY = "pcoc_verify"
private const val PCOC_CREATE = "pcoc_create"
private const val PCOC_ADMIT = "pcoc_admit"
