package net.primal.android.pcoc.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import net.primal.android.user.accounts.active.ActiveAccountStore

@HiltViewModel
class CreatePcocViewModel @Inject constructor(
    private val session: ProduceSession,
    private val activeAccountStore: ActiveAccountStore,
) : ViewModel() {
    val state: StateFlow<CreateUiState> = session.state

    init {
        viewModelScope.launch {
            activeAccountStore.activeUserId.collect { userId -> session.setAccount(userId) }
        }
    }

    fun setEvent(event: CreateUiEvent) {
        when (event) {
            CreateUiEvent.Start -> session.start()
            CreateUiEvent.Stop -> session.stop()
            CreateUiEvent.Reset -> session.reset()
        }
    }
}
