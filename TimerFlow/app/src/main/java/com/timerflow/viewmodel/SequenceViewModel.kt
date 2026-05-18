package com.timerflow.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.timerflow.data.db.TimerDatabase
import com.timerflow.data.model.SequenceWithSteps
import com.timerflow.data.model.TimerSequence
import com.timerflow.data.model.TimerStep
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SequenceViewModel(app: Application) : AndroidViewModel(app) {

    private val dao = TimerDatabase.getInstance(app).timerDao()

    val sequences: StateFlow<List<SequenceWithSteps>> = dao.getAllSequences()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun delete(sequence: TimerSequence) = viewModelScope.launch {
        dao.deleteSequence(sequence)
    }

    suspend fun saveSequence(
        id: Long?,
        name: String,
        isLoop: Boolean,
        steps: List<TimerStep>
    ): Long {
        val seqId = if (id == null || id == 0L) {
            dao.insertSequence(TimerSequence(name = name, isLoop = isLoop))
        } else {
            dao.updateSequence(TimerSequence(id = id, name = name, isLoop = isLoop))
            id
        }
        dao.replaceSteps(seqId, steps.map { it.copy(sequenceId = seqId) })
        return seqId
    }

    suspend fun loadSequence(id: Long): SequenceWithSteps? = dao.getSequenceWithSteps(id)
}
