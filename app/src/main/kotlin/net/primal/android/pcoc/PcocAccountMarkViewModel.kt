package net.primal.android.pcoc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.primal.android.core.utils.authorNameUiFriendly
import net.primal.android.pcoc.enroll.EnrolledPcocStore
import net.primal.android.user.accounts.active.ActiveAccountStore
import net.primal.core.utils.coroutines.DispatcherProvider

/**
 * Local file check for the orange check. No network.
 * [refresh] re-reads the store, so a clear removes the mark on the next start.
 */
@HiltViewModel
class PcocAccountMarkViewModel @Inject constructor(
    private val activeAccountStore: ActiveAccountStore,
    private val store: EnrolledPcocStore,
    private val dispatchers: DispatcherProvider,
) : ViewModel() {

    private val _saved = MutableStateFlow(false)
    val saved = _saved.asStateFlow()

    private val _accountName = MutableStateFlow("")
    val accountName = _accountName.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            val account = activeAccountStore.activeUserAccount()
            val hex = account.pubkey
            _accountName.value = if (hex.isBlank()) "" else account.authorNameUiFriendly()
            _saved.value = withContext(dispatchers.io()) {
                pcocMarkVisible(isOwnProfile = hex.isNotBlank(), hasSavedPcoc = store.contains(hex))
            }
        }
    }
}
