package com.timerflow.data.db

import androidx.room.*
import com.timerflow.data.model.SequenceWithSteps
import com.timerflow.data.model.TimerSequence
import com.timerflow.data.model.TimerStep
import kotlinx.coroutines.flow.Flow

@Dao
interface TimerDao {

    @Transaction
    @Query("SELECT * FROM sequences ORDER BY id DESC")
    fun getAllSequences(): Flow<List<SequenceWithSteps>>

    @Transaction
    @Query("SELECT * FROM sequences WHERE id = :id")
    suspend fun getSequenceWithSteps(id: Long): SequenceWithSteps?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSequence(sequence: TimerSequence): Long

    @Update
    suspend fun updateSequence(sequence: TimerSequence)

    @Delete
    suspend fun deleteSequence(sequence: TimerSequence)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStep(step: TimerStep): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSteps(steps: List<TimerStep>)

    @Delete
    suspend fun deleteStep(step: TimerStep)

    @Query("DELETE FROM steps WHERE sequenceId = :sequenceId")
    suspend fun deleteStepsForSequence(sequenceId: Long)

    @Transaction
    suspend fun replaceSteps(sequenceId: Long, steps: List<TimerStep>) {
        deleteStepsForSequence(sequenceId)
        insertSteps(steps.mapIndexed { i, s -> s.copy(sequenceId = sequenceId, position = i) })
    }
}
