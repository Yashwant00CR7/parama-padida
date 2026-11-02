package com.yashwant.paramapadida.data.local

import androidx.room.*
import com.yashwant.paramapadida.data.model.Course
import kotlinx.coroutines.flow.Flow

@Dao
interface CourseDao {
    @Query("SELECT * FROM courses ORDER BY createdAt DESC")
    fun getAllCourses(): Flow<List<Course>>

    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    fun getCourseById(id: String): Flow<Course?>

    @Query("SELECT * FROM courses WHERE id = :id LIMIT 1")
    suspend fun getCourseByIdSync(id: String): Course?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCourse(course: Course)

    @Update
    suspend fun updateCourse(course: Course)

    @Query("UPDATE courses SET watchedVideos = (SELECT COUNT(*) FROM lessons WHERE courseId = :courseId AND isWatched = 1) WHERE id = :courseId")
    suspend fun refreshWatchedCount(courseId: String)

    @Delete
    suspend fun deleteCourse(course: Course)

    @Query("DELETE FROM courses WHERE id = :id")
    suspend fun deleteCourseById(id: String)
}
