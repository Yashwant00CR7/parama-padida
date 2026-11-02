package com.yashwant.paramapadida

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.lifecycleScope
import com.yashwant.paramapadida.data.repository.CourseRepository
import com.yashwant.paramapadida.engine.KyotoStorageManager
import com.yashwant.paramapadida.ui.course.CourseDetailScreen
import com.yashwant.paramapadida.ui.home.HomeScreen
import com.yashwant.paramapadida.ui.theme.DarkBackground
import com.yashwant.paramapadida.ui.theme.ParamaPadidaTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var repository: CourseRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        repository = CourseRepository(applicationContext)

        // Auto-migrate any previously downloaded videos to Kyoto Player directory
        lifecycleScope.launch(Dispatchers.IO) {
            KyotoStorageManager.migrateLegacyDownloads(applicationContext)
        }

        var sharedUrl by mutableStateOf<String?>(null)
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank() && (sharedText.contains("youtube.com") || sharedText.contains("youtu.be"))) {
                sharedUrl = sharedText
            }
        }

        setContent {
            ParamaPadidaTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = DarkBackground
                ) {
                    val navController = rememberNavController()
                    val coroutineScope = rememberCoroutineScope()

                    LaunchedEffect(sharedUrl) {
                        sharedUrl?.let { url ->
                            coroutineScope.launch {
                                try {
                                    Toast.makeText(this@MainActivity, "Importing shared playlist...", Toast.LENGTH_SHORT).show()
                                    val course = repository.importPlaylist(url, "Shared")
                                    navController.navigate("course/${course.id}")
                                } catch (e: Exception) {
                                    Toast.makeText(this@MainActivity, "Failed to parse: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }

                    NavHost(
                        navController = navController,
                        startDestination = "home"
                    ) {
                        composable("home") {
                            HomeScreen(
                                repository = repository,
                                onCourseClick = { courseId ->
                                    navController.navigate("course/$courseId")
                                }
                            )
                        }

                        composable(
                            route = "course/{courseId}",
                            arguments = listOf(navArgument("courseId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val courseId = backStackEntry.arguments?.getString("courseId") ?: ""
                            CourseDetailScreen(
                                courseId = courseId,
                                repository = repository,
                                onBackClick = { navController.popBackStack() }
                            )
                        }
                    }
                }
            }
        }
    }
}
