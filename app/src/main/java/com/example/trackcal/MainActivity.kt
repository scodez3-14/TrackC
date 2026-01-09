package com.example.trackcal

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.room.*
import com.example.trackcal.ui.theme.TrackCalTheme
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.*

// --- 1. DATA LAYER (ROOM) ---

@Entity(tableName = "food_table")
data class Food(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val name: String,
    val calories: Int,
    val protein: Int,
    val date: Long = System.currentTimeMillis()
)

@Dao
interface FoodDao {
    @Query("SELECT * FROM food_table ORDER BY date DESC")
    fun getAllFoods(): Flow<List<Food>>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(food: Food)
    @Delete
    suspend fun delete(food: Food)
}

@Database(entities = [Food::class], version = 1)
abstract class AppDatabase : RoomDatabase() {
    abstract fun foodDao(): FoodDao
    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null
        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "food_db").build()
                INSTANCE = instance
                instance
            }
        }
    }
}

// --- 2. STORAGE HELPERS ---

fun saveUserSettings(context: Context, cal: Int, prot: Int, apiKey: String) {
    val prefs = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE)
    prefs.edit()
        .putInt("cal_goal", cal)
        .putInt("prot_goal", prot)
        .putString("gemini_api_key", apiKey)
        .apply()
}

fun getSavedCalGoal(context: Context): Int = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).getInt("cal_goal", 2000)
fun getSavedProtGoal(context: Context): Int = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).getInt("prot_goal", 150)
fun getSavedApiKey(context: Context): String = context.getSharedPreferences("user_prefs", Context.MODE_PRIVATE).getString("gemini_api_key", "") ?: ""

// --- 3. UI COMPONENTS ---

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { TrackCalTheme { HomeScreen() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val db = remember { AppDatabase.getDatabase(context) }
    val foodDao = db.foodDao()
    val allFoods by foodDao.getAllFoods().collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var showAddFood by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }

    var calGoal by remember { mutableStateOf(getSavedCalGoal(context)) }
    var protGoal by remember { mutableStateOf(getSavedProtGoal(context)) }
    var apiKey by remember { mutableStateOf(getSavedApiKey(context)) }

    val todayStart = remember(allFoods) {
        Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
        }.timeInMillis
    }
    val todayFoods = allFoods.filter { it.date >= todayStart }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("TrackCal AI", fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        },
        floatingActionButton = {
            if (!showAddFood) {
                FloatingActionButton(onClick = { 
                    if (apiKey.isBlank()) showSettings = true 
                    else showAddFood = true 
                }, containerColor = MaterialTheme.colorScheme.primary) {
                    Text("+", fontSize = 24.sp, color = Color.White)
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            AnimatedVisibility(visible = showSettings) {
                GoalSettingsCard(calGoal, protGoal, apiKey) { c, p, key ->
                    calGoal = c
                    protGoal = p
                    apiKey = key
                    saveUserSettings(context, c, p, key)
                    showSettings = false
                }
            }

            if (showAddFood) {
                AddFoodScreen(
                    apiKey = apiKey,
                    onFoodAdded = { food ->
                        if (food.name.startsWith("Error")) {
                            scope.launch { snackbarHostState.showSnackbar(food.name) }
                        } else {
                            scope.launch { foodDao.insert(food) }
                        }
                        showAddFood = false
                    },
                    onClose = { showAddFood = false }
                )
            } else {
                MainDashboard(todayFoods, allFoods, calGoal, protGoal) { scope.launch { foodDao.delete(it) } }
            }
        }
    }
}

@Composable
fun MainDashboard(todayFoods: List<Food>, allHistory: List<Food>, calGoal: Int, protGoal: Int, onDelete: (Food) -> Unit) {
    val totalCal = todayFoods.sumOf { it.calories }
    val totalProt = todayFoods.sumOf { it.protein }

    Column(modifier = Modifier.padding(16.dp)) {
        DailyProgress(totalCal, calGoal, totalProt, protGoal)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Weekly Consistency", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        BetterHeatmap(allHistory, calGoal, protGoal)
        Spacer(modifier = Modifier.height(24.dp))
        Text("Today's Logs", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 80.dp)) {
            items(items = todayFoods, key = { it.id }) { food ->
                SwipeToDeleteContainer(item = food, onDelete = onDelete) { FoodItemCard(it) }
            }
        }
    }
}

@Composable
fun BetterHeatmap(foods: List<Food>, calGoal: Int, protGoal: Int) {
    val last7Days = remember(foods) {
        (0..6).map { dayOffset ->
            Calendar.getInstance().apply {
                add(Calendar.DAY_OF_YEAR, -dayOffset)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
        }.reversed()
    }

    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        items(last7Days) { dayStart ->
            val dayEnd = dayStart + (24 * 60 * 60 * 1000)
            val daysFood = foods.filter { it.date in dayStart until dayEnd }
            val dCal = daysFood.sumOf { it.calories }
            val dProt = daysFood.sumOf { it.protein }
            val metBoth = dCal >= (calGoal * 0.9) && dProt >= (protGoal * 0.9)
            val metOne = dCal >= (calGoal * 0.9) || dProt >= (protGoal * 0.9)
            val color = when {
                daysFood.isEmpty() -> MaterialTheme.colorScheme.surfaceVariant
                metBoth -> Color(0xFF2E7D32)
                metOne -> Color(0xFF81C784)
                else -> Color(0xFFFFB74D)
            }
            Box(Modifier.size(34.dp).background(color, RoundedCornerShape(8.dp)))
        }
    }
}

@Composable
fun FoodItemCard(food: Food) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), elevation = CardDefaults.cardElevation(2.dp)) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(food.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(8.dp)) {
                    Text("${food.protein}g protein", modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
            Text("${food.calories} kcal", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun AddFoodScreen(apiKey: String, onFoodAdded: (Food) -> Unit, onClose: () -> Unit) {
    var desc by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize().padding(24.dp)) {
        Text("Analyze Meal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Describe what you ate", style = MaterialTheme.typography.bodyMedium, color = Color.Gray)
        OutlinedTextField(value = desc, onValueChange = { desc = it }, modifier = Modifier.fillMaxWidth().height(150.dp).padding(vertical = 16.dp), shape = RoundedCornerShape(16.dp), placeholder = { Text("E.g. Double cheeseburger and fries") })
        Spacer(Modifier.weight(1f))
        if (loading) CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        else {
            Button(onClick = {
                if (desc.isNotBlank()) {
                    loading = true
                    scope.launch { onFoodAdded(fetchNutrientsFromAI(desc, apiKey)); loading = false }
                }
            }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp)) { Text("Analyze & Add") }
            TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
        }
    }
}

@Composable
fun GoalSettingsCard(cGoal: Int, pGoal: Int, currentKey: String, onSave: (Int, Int, String) -> Unit) {
    var cInput by remember { mutableStateOf(cGoal.toString()) }
    var pInput by remember { mutableStateOf(pGoal.toString()) }
    var apiInput by remember { mutableStateOf(currentKey) }
    val uriHandler = LocalUriHandler.current

    Card(modifier = Modifier.padding(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp)) {
            Text("Settings", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(value = apiInput, onValueChange = { apiInput = it }, label = { Text("Gemini API Key") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), visualTransformation = PasswordVisualTransformation())
            TextButton(onClick = { uriHandler.openUri("https://aistudio.google.com/app/apikey") }) { Text("Get free API key here", fontSize = 12.sp) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = cInput, onValueChange = { cInput = it }, label = { Text("Cals") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                OutlinedTextField(value = pInput, onValueChange = { pInput = it }, label = { Text("Prot (g)") }, modifier = Modifier.weight(1f), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            }
            Button(onClick = { onSave(cInput.toIntOrNull() ?: cGoal, pInput.toIntOrNull() ?: pGoal, apiInput) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) { Text("Save Settings") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> SwipeToDeleteContainer(item: T, onDelete: (T) -> Unit, content: @Composable (T) -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { if (it == SwipeToDismissBoxValue.EndToStart) { onDelete(item); true } else false })
    SwipeToDismissBox(state = state, enableDismissFromStartToEnd = false, backgroundContent = {
        val color = if (state.dismissDirection == SwipeToDismissBoxValue.EndToStart) Color.Red.copy(alpha = 0.6f) else Color.Transparent
        Box(Modifier.fillMaxSize().background(color, RoundedCornerShape(12.dp)).padding(horizontal = 20.dp), contentAlignment = Alignment.CenterEnd) { Icon(Icons.Default.Delete, contentDescription = null, tint = Color.White) }
    }, content = { content(item) })
}

@Composable
fun DailyProgress(current: Int, goal: Int, protein: Int, pGoal: Int) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text("Calories Remaining", style = MaterialTheme.typography.labelLarge)
            Text("${(goal - current).coerceAtLeast(0)} kcal", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(progress = { (current.toFloat() / goal).coerceAtMost(1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), strokeCap = androidx.compose.ui.graphics.StrokeCap.Round)
            Spacer(modifier = Modifier.height(8.dp))
            Text("Protein: $protein / $pGoal g", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

suspend fun fetchNutrientsFromAI(input: String, apiKey: String): Food {
    val model = GenerativeModel(modelName = "gemini-2.5-flash", apiKey = apiKey)
    val prompt = "Analyze food: '$input'. Return ONLY JSON: {\"food_name\": \"string\", \"calories\": int, \"protein\": int}"
    return try {
        val response = model.generateContent(prompt).text ?: ""
        val cleanJson = response.trim().removeSurrounding("```json", "```").trim()
        val json = JSONObject(cleanJson)
        Food(name = json.getString("food_name"), calories = json.getInt("calories"), protein = json.getInt("protein"))
    } catch (e: Exception) {
        Food(name = "Error: AI failed or key invalid", calories = 0, protein = 0)
    }
}