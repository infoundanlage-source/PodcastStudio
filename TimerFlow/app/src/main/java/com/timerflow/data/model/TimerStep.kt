package com.timerflow.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "steps",
    foreignKeys = [ForeignKey(
        entity = TimerSequence::class,
        parentColumns = ["id"],
        childColumns = ["sequenceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sequenceId")]
)
data class TimerStep(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sequenceId: Long,
    val position: Int,
    val name: String,
    val durationSeconds: Int,
    val isBreak: Boolean = false
)
