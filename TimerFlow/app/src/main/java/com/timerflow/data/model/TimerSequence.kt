package com.timerflow.data.model

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Relation

@Entity(tableName = "sequences")
data class TimerSequence(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val isLoop: Boolean = false
)

data class SequenceWithSteps(
    @Embedded val sequence: TimerSequence,
    @Relation(parentColumn = "id", entityColumn = "sequenceId")
    val steps: List<TimerStep>
) {
    val totalSeconds: Int get() = steps.sumOf { it.durationSeconds }
}
