package com.rob.speseoffline

import androidx.activity.compose.setContent
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.math.roundToLong

data class NavDestination(val label: String, val icon: ImageVector)

class MainActivity : FragmentActivity() {
    private lateinit var security: SecurityManager
    private lateinit var uiPrefs: UiPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        security = SecurityManager(this)
        uiPrefs = UiPreferences(this)

        setContent {
            var darkMode by remember { mutableStateOf(uiPrefs.darkMode) }
            var unlocked by remember { mutableStateOf(!security.hasPin()) }

            SpeseOfflineTheme(darkTheme = darkMode) {
                if (!unlocked) {
                    LockScreen(
                        biometricEnabled = security.biometricEnabled,
                        verifyPin = security::verifyPin,
                        onUnlocked = { unlocked = true },
                        onBiometric = { showBiometricPrompt { unlocked = true } }
                    )
                } else {
                    ExpenseApp(
                        repository = remember { ExpenseRepository(this) },
                        security = security,
                        darkMode = darkMode,
                        onDarkModeChange = {
                            darkMode = it
                            uiPrefs.darkMode = it
                        },
                        onLockNow = {
                            if (security.hasPin()) unlocked = false
                        },
                        onBiometricRequest = { success -> showBiometricPrompt(success) }
                    )
                }
            }
        }
    }

    private fun showBiometricPrompt(onSuccess: () -> Unit) {
        val allowed = BiometricManager.Authenticators.BIOMETRIC_STRONG
        val manager = BiometricManager.from(this)
        if (manager.canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) return

        val executor = Executor { command -> runOnUiThread(command) }
        val prompt = BiometricPrompt(
            this,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Sblocca Spese Offline")
            .setSubtitle("Autenticati con la biometria del dispositivo")
            .setAllowedAuthenticators(allowed)
            .setNegativeButtonText("Usa PIN")
            .build()

        prompt.authenticate(info)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseApp(
    repository: ExpenseRepository,
    security: SecurityManager,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onLockNow: () -> Unit,
    onBiometricRequest: ((() -> Unit) -> Unit)
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var month by remember { mutableStateOf(YearMonth.now()) }
    var tab by remember { mutableIntStateOf(0) }
    var transactions by remember { mutableStateOf(emptyList<Transaction>()) }
    var categories by remember { mutableStateOf(emptyList<Category>()) }
    var expenseTotals by remember { mutableStateOf(emptyList<CategoryTotal>()) }
    var summary by remember { mutableStateOf(MonthSummary(month.toString(), 0, 0)) }
    var budget by remember { mutableLongStateOf(0L) }
    var categoryBudgets by remember { mutableStateOf(emptyList<CategoryBudget>()) }
    var history by remember { mutableStateOf(emptyList<MonthSummary>()) }
    var yearHistory by remember { mutableStateOf(emptyList<MonthSummary>()) }
    var recurring by remember { mutableStateOf(emptyList<RecurringTransaction>()) }

    var editTransaction by remember { mutableStateOf<Transaction?>(null) }
    var showTransactionDialog by remember { mutableStateOf(false) }
    var showBudgetDialog by remember { mutableStateOf(false) }
    var showCategoryBudgets by remember { mutableStateOf(false) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showRecurringDialog by remember { mutableStateOf(false) }
    var showSecurityDialog by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            repository.materializeRecurring(month)
            val ym = month.toString()
            val tx = repository.transactionsForMonth(ym)
            val cats = repository.categories()
            val totals = repository.categoryTotals(ym)
            val sm = repository.monthSummary(ym)
            val b = repository.getMonthlyBudget(ym)
            val cb = repository.categoryBudgets(ym)
            val h = repository.history(month, 6)
            val yh = repository.yearHistory(month.year)
            val rec = repository.recurringTransactions()
            withContext(Dispatchers.Main) {
                transactions = tx
                categories = cats
                expenseTotals = totals
                summary = sm
                budget = b
                categoryBudgets = cb
                history = h
                yearHistory = yh
                recurring = rec
            }
        }
    }

    LaunchedEffect(month) { reload() }

    val jsonExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(repository.exportBackupJson().toByteArray())
                    } ?: error("output")
                }.isSuccess
            }
            message = if (ok) "Backup esportato correttamente." else "Esportazione non riuscita."
        }
    }

    val jsonImport = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: return@runCatching false
                    repository.importBackupJson(text)
                }.getOrDefault(false)
            }
            if (ok) reload()
            message = if (ok) "Backup importato." else "Backup non valido o incompatibile."
        }
    }

    val csvExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) scope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        it.write(repository.exportCsv(month.toString()).toByteArray(Charsets.UTF_8))
                    } ?: error("output")
                }.isSuccess
            }
            message = if (ok) "CSV esportato: puoi aprirlo anche con Excel." else "Esportazione CSV non riuscita."
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Spese Offline", fontWeight = FontWeight.Bold)
                        Text(
                            "Privato • locale • senza cloud",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar(tonalElevation = 10.dp) {
                val destinations = listOf(
                    NavDestination("Home", Icons.Filled.Home),
                    NavDestination("Movimenti", Icons.Filled.List),
                    NavDestination("Analisi", Icons.Filled.Assessment),
                    NavDestination("Gestione", Icons.Filled.Settings)
                )
                destinations.forEachIndexed { i, destination ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = {
                            Icon(
                                imageVector = destination.icon,
                                contentDescription = destination.label
                            )
                        },
                        label = { Text(destination.label) },
                        alwaysShowLabel = true
                    )
                }
            }
        },
        floatingActionButton = {
            if (tab == 0 || tab == 1) {
                ExtendedFloatingActionButton(
                    onClick = {
                        editTransaction = null
                        showTransactionDialog = true
                    },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Nuovo movimento") },
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            }
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (tab != 3) {
                MonthSelector(
                    month = month,
                    onPrevious = { month = month.minusMonths(1) },
                    onNext = { month = month.plusMonths(1) }
                )
            }

            when (tab) {
                0 -> DashboardScreen(
                    summary = summary,
                    budget = budget,
                    totals = expenseTotals,
                    budgets = categoryBudgets,
                    recent = transactions.take(6),
                    onBudget = { showBudgetDialog = true }
                )
                1 -> TransactionsScreenV3(
                    month = month,
                    categories = categories,
                    repository = repository,
                    refreshKey = transactions.hashCode(),
                    onEdit = {
                        editTransaction = it
                        showTransactionDialog = true
                    },
                    onDuplicate = { tx ->
                        scope.launch(Dispatchers.IO) {
                            repository.duplicateTransaction(tx.id)
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                    onDelete = { tx ->
                        scope.launch(Dispatchers.IO) {
                            repository.deleteTransaction(tx.id)
                            withContext(Dispatchers.Main) { reload() }
                        }
                    }
                )
                2 -> AnalyticsScreen(
                    month = month,
                    summary = summary,
                    history = history,
                    year = yearHistory,
                    categoryTotals = expenseTotals
                )
                3 -> ManageScreenV3(
                    month = month,
                    recurring = recurring,
                    darkMode = darkMode,
                    onDarkModeChange = onDarkModeChange,
                    onCategories = { showCategoryManager = true },
                    onBudget = { showBudgetDialog = true },
                    onCategoryBudgets = { showCategoryBudgets = true },
                    onAddRecurring = { showRecurringDialog = true },
                    onToggleRecurring = { id, active ->
                        scope.launch(Dispatchers.IO) {
                            repository.setRecurringActive(id, active)
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                    onDeleteRecurring = { id ->
                        scope.launch(Dispatchers.IO) {
                            repository.deleteRecurring(id)
                            withContext(Dispatchers.Main) { reload() }
                        }
                    },
                    onExportJson = { jsonExport.launch("spese-offline-backup-${LocalDate.now()}.json") },
                    onImportJson = { jsonImport.launch(arrayOf("application/json", "text/plain")) },
                    onExportCsv = { csvExport.launch("spese-${month}.csv") },
                    onSecurity = { showSecurityDialog = true },
                    onLockNow = onLockNow
                )
            }
        }
    }

    if (showTransactionDialog) {
        TransactionDialog(
            categories = categories,
            existing = editTransaction,
            onDismiss = { showTransactionDialog = false },
            onSave = { cents, catId, note, date, type ->
                scope.launch(Dispatchers.IO) {
                    val existing = editTransaction
                    if (existing == null) repository.addTransaction(cents, catId, note, date, type)
                    else repository.updateTransaction(existing.id, cents, catId, note, date, type)

                    withContext(Dispatchers.Main) {
                        showTransactionDialog = false
                        editTransaction = null
                        month = YearMonth.parse(date.substring(0, 7))
                        reload()
                    }
                }
            }
        )
    }

    if (showBudgetDialog) {
        MoneyDialogV3(
            title = "Budget ${monthLabel(month)}",
            initialCents = budget,
            onDismiss = { showBudgetDialog = false }
        ) { cents ->
            scope.launch(Dispatchers.IO) {
                repository.setMonthlyBudget(month.toString(), cents)
                withContext(Dispatchers.Main) { showBudgetDialog = false; reload() }
            }
        }
    }

    if (showCategoryBudgets) {
        CategoryBudgetsDialogV3(
            budgets = categoryBudgets,
            totals = expenseTotals,
            onDismiss = { showCategoryBudgets = false },
            onSave = { categoryId, cents ->
                scope.launch(Dispatchers.IO) {
                    repository.setCategoryBudget(month.toString(), categoryId, cents)
                    withContext(Dispatchers.Main) { reload() }
                }
            }
        )
    }

    if (showCategoryManager) {
        CategoryManagerDialog(
            categories = categories,
            onDismiss = { showCategoryManager = false },
            onAdd = { name, icon, color ->
                scope.launch(Dispatchers.IO) {
                    repository.addCategory(name, icon, color)
                    withContext(Dispatchers.Main) { reload() }
                }
            },
            onEdit = { id, name, icon, color ->
                scope.launch(Dispatchers.IO) {
                    repository.updateCategory(id, name, icon, color)
                    withContext(Dispatchers.Main) { reload() }
                }
            },
            onDelete = { id, callback ->
                scope.launch(Dispatchers.IO) {
                    val ok = repository.deleteCategory(id)
                    withContext(Dispatchers.Main) { reload(); callback(ok) }
                }
            }
        )
    }

    if (showRecurringDialog) {
        RecurringDialog(
            categories = categories,
            onDismiss = { showRecurringDialog = false },
            onSave = { cents, catId, note, day, type ->
                scope.launch(Dispatchers.IO) {
                    repository.addRecurring(cents, catId, note, day, type)
                    repository.materializeRecurring(month)
                    withContext(Dispatchers.Main) { showRecurringDialog = false; reload() }
                }
            }
        )
    }

    if (showSecurityDialog) {
        SecurityDialog(
            security = security,
            onDismiss = { showSecurityDialog = false },
            canUseBiometric = BiometricManager.from(context).canAuthenticate(
                BiometricManager.Authenticators.BIOMETRIC_STRONG
            ) == BiometricManager.BIOMETRIC_SUCCESS,
            onTestBiometric = { onBiometricRequest {} }
        )
    }

    if (message != null) {
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Spese Offline") },
            text = { Text(message!!) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } }
        )
    }
}

@Composable
fun DashboardScreen(
    summary: MonthSummary,
    budget: Long,
    totals: List<CategoryTotal>,
    budgets: List<CategoryBudget>,
    recent: List<Transaction>,
    onBudget: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Panoramica", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Il tuo mese in un colpo d’occhio",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { BalanceCard(summary) }
        item { BudgetCard(summary.expenseCents, budget, onBudget) }
        item { SectionTitle("Spese per categoria") }
        if (totals.isEmpty()) item { EmptyCard("Nessuna uscita registrata questo mese.") }
        else item { CategoryBarsV3(totals, budgets) }
        item { SectionTitle("Ultimi movimenti") }
        if (recent.isEmpty()) item { EmptyCard("Aggiungi il primo movimento.") }
        else items(recent, key = { it.id }) { TransactionCompactRow(it) }
    }
}

@Composable
fun BalanceCard(summary: MonthSummary) {
    val positive = summary.balanceCents >= 0
    val accent = if (positive) Color(0xFF2E7D5B) else MaterialTheme.colorScheme.error
    val brush = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
        )
    )

    Card(
        shape = RoundedCornerShape(28.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(brush)
                .padding(22.dp)
        ) {
            Text(
                "Saldo del mese",
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelLarge
            )
            Text(
                signedMoney(summary.balanceCents),
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(18.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SummaryMiniCard(
                    modifier = Modifier.weight(1f),
                    label = "Entrate",
                    amount = money(summary.incomeCents),
                    symbol = "↑",
                    tint = Color(0xFF1E6F50)
                )
                SummaryMiniCard(
                    modifier = Modifier.weight(1f),
                    label = "Uscite",
                    amount = money(summary.expenseCents),
                    symbol = "↓",
                    tint = Color(0xFF9A3F48)
                )
            }
        }
    }
}

@Composable
fun SummaryMiniCard(
    modifier: Modifier,
    label: String,
    amount: String,
    symbol: String,
    tint: Color
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = tint.copy(alpha = 0.14f)
                ) {
                    Text(
                        symbol,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
                        color = tint,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(7.dp))
                Text(label, style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(8.dp))
            Text(amount, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

@Composable
fun BudgetCard(spent: Long, budget: Long, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("Budget spese", fontWeight = FontWeight.Bold)
            if (budget <= 0) {
                Text("Tocca per impostare il limite mensile.", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(8.dp))
                val p = (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
                LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                val remaining = budget - spent
                Text(
                    if (remaining >= 0) "${money(spent)} di ${money(budget)} • restano ${money(remaining)}"
                    else "Superato di ${money(-remaining)}"
                )
            }
        }
    }
}

@Composable
fun TransactionsScreenV3(
    month: YearMonth,
    categories: List<Category>,
    repository: ExpenseRepository,
    refreshKey: Int,
    onEdit: (Transaction) -> Unit,
    onDuplicate: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var categoryId by remember { mutableStateOf<Long?>(null) }
    var type by remember { mutableStateOf<TxType?>(null) }
    var rows by remember { mutableStateOf(emptyList<Transaction>()) }

    LaunchedEffect(month, query, categoryId, type, refreshKey) {
        rows = withContext(Dispatchers.IO) {
            repository.transactionsForMonth(month.toString(), query, categoryId, type)
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Column {
                Text("Movimenti", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Cerca, filtra e gestisci ogni operazione",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Cerca movimenti") },
                placeholder = { Text("Categoria o descrizione") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(selected = type == null, onClick = { type = null }, label = { Text("Tutti") })
                FilterChip(selected = type == TxType.EXPENSE, onClick = { type = TxType.EXPENSE }, label = { Text("Uscite") })
                FilterChip(selected = type == TxType.INCOME, onClick = { type = TxType.INCOME }, label = { Text("Entrate") })
                CategoryFilterV3(categories, categoryId) { categoryId = it }
            }
        }

        if (rows.isEmpty()) item { EmptyCard("Nessun movimento corrispondente.") }
        else items(rows, key = { it.id }) { tx ->
            TransactionRow(tx, onEdit, onDuplicate, onDelete)
        }
    }
}

@Composable
fun AnalyticsScreen(
    month: YearMonth,
    summary: MonthSummary,
    history: List<MonthSummary>,
    year: List<MonthSummary>,
    categoryTotals: List<CategoryTotal>
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Column {
                Text("Analisi", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Trend, categorie e andamento annuale",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { SectionTitle("Distribuzione uscite") }
        if (categoryTotals.isEmpty()) item { EmptyCard("Nessun dato per il grafico.") }
        else item { PieCard(categoryTotals) }

        item { SectionTitle("Ultimi 6 mesi") }
        item { SixMonthCard(history) }

        item { SectionTitle("Anno ${month.year}") }
        item { AnnualCard(year) }

        item {
            val prev = history.dropLast(1).lastOrNull()
            Card {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text("Confronto mese precedente", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    if (prev == null || prev.expenseCents == 0L) {
                        Text("Confronto non disponibile.")
                    } else {
                        val delta = summary.expenseCents - prev.expenseCents
                        val pct = delta.toDouble() / prev.expenseCents.toDouble() * 100.0
                        Text(
                            "${if (delta >= 0) "+" else ""}${money(delta)} (${String.format(Locale.ITALY, "%.1f", pct)}%)"
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PieCard(totals: List<CategoryTotal>) {
    val total = totals.sumOf { it.totalCents }.coerceAtLeast(1L)
    val holeColor = MaterialTheme.colorScheme.surface
    Card(shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(190.dp)) {
                    var start = -90f
                    totals.forEach { item ->
                        val sweep = 360f * item.totalCents.toFloat() / total.toFloat()
                        drawArc(
                            color = Color(item.categoryColorArgb),
                            startAngle = start,
                            sweepAngle = sweep,
                            useCenter = true
                        )
                        start += sweep
                    }
                    drawCircle(
                        color = holeColor,
                        radius = size.minDimension * .24f
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Totale", style = MaterialTheme.typography.labelSmall)
                    Text(money(total), fontWeight = FontWeight.Bold)
                }
            }
            totals.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(Color(item.categoryColorArgb)))
                    Spacer(Modifier.width(8.dp))
                    Text("${item.categoryIcon} ${item.categoryName}", modifier = Modifier.weight(1f))
                    Text(money(item.totalCents), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun SixMonthCard(history: List<MonthSummary>) {
    val max = history.maxOfOrNull { it.expenseCents }?.coerceAtLeast(1) ?: 1L
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            history.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(shortMonthLabel(YearMonth.parse(m.yearMonth)), modifier = Modifier.width(46.dp))
                    LinearProgressIndicator(
                        progress = { m.expenseCents.toFloat() / max.toFloat() },
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(money(m.expenseCents), modifier = Modifier.width(90.dp))
                }
            }
        }
    }
}

@Composable
fun AnnualCard(year: List<MonthSummary>) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val totalExpenses = year.sumOf { it.expenseCents }
            val totalIncome = year.sumOf { it.incomeCents }
            Text("Entrate: ${money(totalIncome)}")
            Text("Uscite: ${money(totalExpenses)}")
            Text("Saldo: ${signedMoney(totalIncome - totalExpenses)}", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            year.forEach { m ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(shortMonthLabel(YearMonth.parse(m.yearMonth)))
                    Text("${money(m.expenseCents)} / ${money(m.incomeCents)}")
                }
            }
        }
    }
}

@Composable
fun ManageScreenV3(
    month: YearMonth,
    recurring: List<RecurringTransaction>,
    darkMode: Boolean,
    onDarkModeChange: (Boolean) -> Unit,
    onCategories: () -> Unit,
    onBudget: () -> Unit,
    onCategoryBudgets: () -> Unit,
    onAddRecurring: () -> Unit,
    onToggleRecurring: (Long, Boolean) -> Unit,
    onDeleteRecurring: (Long) -> Unit,
    onExportJson: () -> Unit,
    onImportJson: () -> Unit,
    onExportCsv: () -> Unit,
    onSecurity: () -> Unit,
    onLockNow: () -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 100.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column {
                Text("Gestione", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Personalizza budget, sicurezza e dati",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item { SectionTitle("Organizzazione") }
        item { ActionCard("Categorie", "Personalizza nome, icona e colore", onCategories) }
        item { ActionCard("Budget mensile", "Limite generale per ${monthLabel(month)}", onBudget) }
        item { ActionCard("Budget per categoria", "Limiti specifici per ogni categoria", onCategoryBudgets) }

        item { SectionTitle("Aspetto e sicurezza") }
        item {
            Card {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("Tema scuro", fontWeight = FontWeight.Bold)
                        Text("Salvato solo sul dispositivo", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = darkMode, onCheckedChange = onDarkModeChange)
                }
            }
        }
        item { ActionCard("PIN e biometria", "Proteggi l'apertura dell'app", onSecurity) }
        item { ActionCard("Blocca adesso", "Richiede nuovamente PIN/biometria", onLockNow) }

        item { SectionTitle("Movimenti ricorrenti") }
        item {
            Button(onClick = onAddRecurring, modifier = Modifier.fillMaxWidth()) {
                Text("+ Aggiungi ricorrenza")
            }
        }
        if (recurring.isEmpty()) item { EmptyCard("Nessuna ricorrenza configurata.") }
        else items(recurring, key = { it.id }) { r ->
            Card {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.categoryName, fontWeight = FontWeight.Bold)
                            Text("${if (r.type == TxType.EXPENSE) "Uscita" else "Entrata"} • ${money(r.amountCents)} • giorno ${r.dayOfMonth}")
                            if (r.note.isNotBlank()) Text(r.note, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = r.active, onCheckedChange = { onToggleRecurring(r.id, it) })
                    }
                    TextButton(onClick = { onDeleteRecurring(r.id) }) { Text("Elimina ricorrenza") }
                }
            }
        }

        item { SectionTitle("Dati") }
        item { ActionCard("Esporta CSV", "Esporta ${monthLabel(month)} per Excel", onExportCsv) }
        item { ActionCard("Backup completo JSON", "Salva categorie, budget, movimenti e ricorrenze", onExportJson) }
        item { ActionCard("Ripristina backup", "Sostituisce i dati correnti con il file selezionato", onImportJson) }
        item {
            Card {
                Text(
                    "Privacy: l'app non richiede il permesso INTERNET. Tutte le operazioni avvengono localmente.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
fun TransactionRow(
    tx: Transaction,
    onEdit: (Transaction) -> Unit,
    onDuplicate: (Transaction) -> Unit,
    onDelete: (Transaction) -> Unit
) {
    var menu by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(Color(tx.categoryColorArgb.toInt())),
                    contentAlignment = Alignment.Center
                ) { Text(tx.categoryIcon) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tx.categoryName, fontWeight = FontWeight.Bold)
                    Text(formatDate(tx.dateIso), style = MaterialTheme.typography.labelSmall)
                    if (tx.note.isNotBlank()) Text(tx.note, style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        (if (tx.type == TxType.INCOME) "+" else "−") + money(tx.amountCents),
                        fontWeight = FontWeight.Bold,
                        color = if (tx.type == TxType.INCOME) Color(0xFF2E7D5B) else MaterialTheme.colorScheme.error
                    )
                    TextButton(onClick = { menu = true }) { Text("•••") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Modifica") }, onClick = { menu = false; onEdit(tx) })
                        DropdownMenuItem(text = { Text("Duplica") }, onClick = { menu = false; onDuplicate(tx) })
                        DropdownMenuItem(text = { Text("Elimina") }, onClick = { menu = false; onDelete(tx) })
                    }
                }
            }
        }
    }
}

@Composable
fun TransactionCompactRow(tx: Transaction) {
    Card {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(tx.categoryIcon, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(tx.categoryName, fontWeight = FontWeight.Bold)
                if (tx.note.isNotBlank()) Text(tx.note, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                (if (tx.type == TxType.INCOME) "+" else "−") + money(tx.amountCents),
                fontWeight = FontWeight.Bold,
                color = if (tx.type == TxType.INCOME) Color(0xFF2E7D5B) else MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun CategoryBarsV3(totals: List<CategoryTotal>, budgets: List<CategoryBudget>) {
    val max = totals.maxOfOrNull { it.totalCents }?.coerceAtLeast(1) ?: 1L
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            totals.forEach { row ->
                val catBudget = budgets.firstOrNull { it.categoryId == row.categoryId }?.amountCents ?: 0L
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${row.categoryIcon} ${row.categoryName}")
                        Text(money(row.totalCents), fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(5.dp))
                    LinearProgressIndicator(
                        progress = {
                            if (catBudget > 0) (row.totalCents.toFloat() / catBudget).coerceIn(0f, 1f)
                            else row.totalCents.toFloat() / max
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (catBudget > 0) {
                        Text(
                            if (row.totalCents <= catBudget) "Budget ${money(catBudget)}"
                            else "Superato di ${money(row.totalCents - catBudget)}",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionDialog(
    categories: List<Category>,
    existing: Transaction?,
    onDismiss: () -> Unit,
    onSave: (Long, Long, String, String, TxType) -> Unit
) {
    var amount by remember(existing) {
        mutableStateOf(existing?.let { String.format(Locale.US, "%.2f", it.amountCents / 100.0) } ?: "")
    }
    var note by remember(existing) { mutableStateOf(existing?.note ?: "") }
    var type by remember(existing) { mutableStateOf(existing?.type ?: TxType.EXPENSE) }
    var selected by remember(existing, categories) {
        mutableStateOf(categories.firstOrNull { it.id == existing?.categoryId } ?: categories.firstOrNull())
    }
    var date by remember(existing) { mutableStateOf(existing?.let { LocalDate.parse(it.dateIso) } ?: LocalDate.now()) }
    var expanded by remember { mutableStateOf(false) }
    var datePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuovo movimento" else "Modifica movimento") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = type == TxType.EXPENSE,
                        onClick = { type = TxType.EXPENSE },
                        label = { Text("Uscita") }
                    )
                    FilterChip(
                        selected = type == TxType.INCOME,
                        onClick = { type = TxType.INCOME },
                        label = { Text("Entrata") }
                    )
                }

                MoneyFieldV3(amount) { amount = it }

                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selected?.let { "${it.icon} ${it.name}" } ?: "Nessuna categoria",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categoria") },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text("${c.icon} ${c.name}") },
                                onClick = { selected = c; expanded = false }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("Descrizione / nota") },
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedButton(onClick = { datePicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Data: ${formatDate(date.toString())}")
                }

                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cents = parseMoney(amount)
                when {
                    cents <= 0 -> error = "Inserisci un importo valido."
                    selected == null -> error = "Seleziona una categoria."
                    else -> onSave(cents, selected!!.id, note, date.toString(), type)
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )

    if (datePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.toEpochDay() * 86_400_000L)
        DatePickerDialog(
            onDismissRequest = { datePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { date = LocalDate.ofEpochDay(it / 86_400_000L) }
                    datePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { datePicker = false }) { Text("Annulla") } }
        ) { DatePicker(state) }
    }
}

@Composable
fun CategoryManagerDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onAdd: (String, String, Long) -> Unit,
    onEdit: (Long, String, String, Long) -> Unit,
    onDelete: (Long, (Boolean) -> Unit) -> Unit
) {
    var editing by remember { mutableStateOf<Category?>(null) }
    var adding by remember { mutableStateOf(false) }
    var warning by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Categorie") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { adding = true }, modifier = Modifier.fillMaxWidth()) { Text("+ Nuova categoria") }
                categories.forEach { c ->
                    Card(onClick = { editing = c }) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(Color(c.colorArgb.toInt())),
                                contentAlignment = Alignment.Center
                            ) { Text(c.icon) }
                            Spacer(Modifier.width(10.dp))
                            Text(c.name, modifier = Modifier.weight(1f))
                            TextButton(onClick = {
                                onDelete(c.id) { ok ->
                                    if (!ok) warning = "Questa categoria è già usata e non può essere eliminata."
                                }
                            }) { Text("Elimina") }
                        }
                    }
                }
                warning?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )

    if (adding) {
        CategoryEditDialog(
            existing = null,
            onDismiss = { adding = false },
            onSave = { name, icon, color -> onAdd(name, icon, color); adding = false }
        )
    }
    editing?.let { c ->
        CategoryEditDialog(
            existing = c,
            onDismiss = { editing = null },
            onSave = { name, icon, color -> onEdit(c.id, name, icon, color); editing = null }
        )
    }
}

@Composable
fun CategoryEditDialog(
    existing: Category?,
    onDismiss: () -> Unit,
    onSave: (String, String, Long) -> Unit
) {
    val palette = listOf(
        0xFF5E9C76L, 0xFF7B61AEL, 0xFF3D7EABL, 0xFFC47B4CL,
        0xFFB95C70L, 0xFF6D7F9EL, 0xFFB26B9DL, 0xFFB48A44L, 0xFF757575L
    )
    var name by remember(existing) { mutableStateOf(existing?.name ?: "") }
    var icon by remember(existing) { mutableStateOf(existing?.icon ?: "●") }
    var color by remember(existing) { mutableLongStateOf(existing?.colorArgb ?: palette.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nuova categoria" else "Modifica categoria") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Nome") })
                OutlinedTextField(value = icon, onValueChange = { icon = it.take(3) }, label = { Text("Icona / emoji") })
                Text("Colore")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    palette.forEach { candidate ->
                        Box(
                            Modifier
                                .size(if (candidate == color) 40.dp else 32.dp)
                                .clip(CircleShape)
                                .background(Color(candidate.toInt()))
                                .clickable { color = candidate }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { if (name.isNotBlank()) onSave(name.trim(), icon.ifBlank { "●" }, color) }) {
                Text("Salva")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun CategoryBudgetsDialogV3(
    budgets: List<CategoryBudget>,
    totals: List<CategoryTotal>,
    onDismiss: () -> Unit,
    onSave: (Long, Long) -> Unit
) {
    var editing by remember { mutableStateOf<CategoryBudget?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Budget per categoria") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                budgets.forEach { b ->
                    val spent = totals.firstOrNull { it.categoryId == b.categoryId }?.totalCents ?: 0L
                    Card(onClick = { editing = b }) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(b.categoryName, fontWeight = FontWeight.Bold)
                                Text("Speso ${money(spent)}", style = MaterialTheme.typography.labelSmall)
                            }
                            Text(if (b.amountCents > 0) money(b.amountCents) else "Nessun limite")
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
    editing?.let { b ->
        MoneyDialogV3(
            title = b.categoryName,
            initialCents = b.amountCents,
            onDismiss = { editing = null }
        ) { cents -> onSave(b.categoryId, cents); editing = null }
    }
}

@Composable
fun MoneyDialogV3(title: String, initialCents: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var amount by remember(initialCents) {
        mutableStateOf(if (initialCents > 0) String.format(Locale.US, "%.2f", initialCents / 100.0) else "")
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                MoneyFieldV3(amount) { amount = it }
                Spacer(Modifier.height(6.dp))
                Text("Inserisci 0 per rimuovere il limite.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { Button(onClick = { onSave(parseMoney(amount)) }) { Text("Salva") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecurringDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Long, Long, String, Int, TxType) -> Unit
) {
    var amount by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var day by remember { mutableStateOf("1") }
    var type by remember { mutableStateOf(TxType.EXPENSE) }
    var selected by remember(categories) { mutableStateOf(categories.firstOrNull()) }
    var expanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Movimento ricorrente") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = type == TxType.EXPENSE, onClick = { type = TxType.EXPENSE }, label = { Text("Uscita") })
                    FilterChip(selected = type == TxType.INCOME, onClick = { type = TxType.INCOME }, label = { Text("Entrata") })
                }
                MoneyFieldV3(amount) { amount = it }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = !expanded }) {
                    OutlinedTextField(
                        value = selected?.name ?: "",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Categoria") },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        categories.forEach { c ->
                            DropdownMenuItem(text = { Text("${c.icon} ${c.name}") }, onClick = {
                                selected = c; expanded = false
                            })
                        }
                    }
                }
                OutlinedTextField(
                    value = day,
                    onValueChange = { day = it.filter(Char::isDigit).take(2) },
                    label = { Text("Giorno del mese (1-31)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("Nota") })
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            Button(onClick = {
                val cents = parseMoney(amount)
                val d = day.toIntOrNull() ?: 0
                when {
                    cents <= 0 -> error = "Importo non valido."
                    selected == null -> error = "Seleziona una categoria."
                    d !in 1..31 -> error = "Il giorno deve essere tra 1 e 31."
                    else -> onSave(cents, selected!!.id, note, d, type)
                }
            }) { Text("Salva") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annulla") } }
    )
}

@Composable
fun SecurityDialog(
    security: SecurityManager,
    canUseBiometric: Boolean,
    onDismiss: () -> Unit,
    onTestBiometric: () -> Unit
) {
    var hasPin by remember { mutableStateOf(security.hasPin()) }
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var biometric by remember { mutableStateOf(security.biometricEnabled) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sicurezza") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!hasPin) {
                    Text("Crea un PIN numerico da 4 a 12 cifre.")
                    OutlinedTextField(
                        value = pin1, onValueChange = { pin1 = it.filter(Char::isDigit).take(12) },
                        label = { Text("PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    OutlinedTextField(
                        value = pin2, onValueChange = { pin2 = it.filter(Char::isDigit).take(12) },
                        label = { Text("Ripeti PIN") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword)
                    )
                    Button(onClick = {
                        when {
                            pin1.length !in 4..12 -> error = "Il PIN deve avere 4-12 cifre."
                            pin1 != pin2 -> error = "I PIN non coincidono."
                            else -> {
                                security.setPin(pin1); hasPin = true; error = null; pin1 = ""; pin2 = ""
                            }
                        }
                    }) { Text("Attiva PIN") }
                } else {
                    Text("PIN attivo.", fontWeight = FontWeight.Bold)
                    if (canUseBiometric) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Sblocco biometrico")
                            Switch(
                                checked = biometric,
                                onCheckedChange = {
                                    biometric = it
                                    security.biometricEnabled = it
                                    if (it) onTestBiometric()
                                }
                            )
                        }
                    } else {
                        Text("Biometria forte non disponibile sul dispositivo.", style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {
                        security.clearPin()
                        hasPin = false
                        biometric = false
                    }) { Text("Rimuovi protezione") }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Chiudi") } }
    )
}

@Composable
fun LockScreen(
    biometricEnabled: Boolean,
    verifyPin: (String) -> Boolean,
    onUnlocked: () -> Unit,
    onBiometric: () -> Unit
) {
    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Card {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("Spese Offline", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("App protetta")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it.filter(Char::isDigit).take(12); error = false },
                    label = { Text("PIN") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true
                )
                if (error) Text("PIN non corretto.", color = MaterialTheme.colorScheme.error)
                Button(
                    onClick = {
                        if (verifyPin(pin)) onUnlocked() else error = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Sblocca") }
                if (biometricEnabled) {
                    OutlinedButton(onClick = onBiometric, modifier = Modifier.fillMaxWidth()) {
                        Text("Usa biometria")
                    }
                }
            }
        }
    }
}

@Composable
fun CategoryFilterV3(categories: List<Category>, selected: Long?, onSelect: (Long?) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        FilterChip(
            selected = selected != null,
            onClick = { expanded = true },
            label = { Text(categories.firstOrNull { it.id == selected }?.name ?: "Categoria") }
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Tutte") }, onClick = { onSelect(null); expanded = false })
            categories.forEach { c ->
                DropdownMenuItem(text = { Text("${c.icon} ${c.name}") }, onClick = {
                    onSelect(c.id); expanded = false
                })
            }
        }
    }
}

@Composable
fun MonthSelector(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onPrevious) { Text("‹", fontSize = 26.sp) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(monthLabel(month), fontWeight = FontWeight.Bold)
                Text("Tocca le frecce per cambiare mese", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onNext) { Text("›", fontSize = 26.sp) }
        }
    }
}

@Composable
fun ActionCard(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun EmptyCard(text: String) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(18.dp))
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun MoneyFieldV3(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text("Importo (€)") },
        placeholder = { Text("es. 24,90") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )
}

fun parseMoney(text: String): Long =
    ((text.trim().replace(",", ".").toDoubleOrNull() ?: 0.0) * 100.0).roundToLong()

fun money(cents: Long): String =
    NumberFormat.getCurrencyInstance(Locale.ITALY).format(cents / 100.0)

fun signedMoney(cents: Long): String =
    (if (cents > 0) "+" else "") + money(cents)

fun formatDate(iso: String): String =
    LocalDate.parse(iso).format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))

fun monthLabel(month: YearMonth): String {
    val locale = Locale.ITALIAN
    val name = month.month.getDisplayName(TextStyle.FULL, locale)
        .replaceFirstChar { it.uppercase(locale) }
    return "$name ${month.year}"
}

fun shortMonthLabel(month: YearMonth): String =
    month.month.getDisplayName(TextStyle.SHORT, Locale.ITALIAN)
        .replace(".", "")
        .replaceFirstChar { it.uppercase(Locale.ITALIAN) }
