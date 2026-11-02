package com.yashwant.paramapadida.data.local

import androidx.room.*
import com.yashwant.paramapadida.data.model.Note
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM notes WHERE lessonId = :lessonId ORDER BY timestampMs ASC")
    fun getNotesForLesson(lessonId: String): Flow<List<Note>>

    @Query("SELECT * FROM notes WHERE courseId = :courseId ORDER BY createdAt DESC")
    fun getNotesForCourse(courseId: String): Flow<List<Note>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: Note): Long

    @Delete
    suspend fun deleteNote(note: Note)

    @Query("DELETE FROM notes WHERE id = :noteId")
    suspend fun deleteNoteById(noteId: Long)
}
