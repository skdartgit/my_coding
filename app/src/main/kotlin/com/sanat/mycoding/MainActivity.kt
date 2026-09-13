package com.sanat.mycoding

import android.app.Activity
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.platform.LocalContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

private val Primary = Color(0xFF3F5BD9)
private val Ink = Color(0xFF1F2937)
private val Muted = Color(0xFF667085)
private val Canvas = Color(0xFFF5F7FC)
private val Card = Color(0xFFFFFFFF)
private val Border = Color(0xFFE1E6F0)
private val Success = Color(0xFF128A78)
private val Danger = Color(0xFFD64545)

private data class CodeItem(
    val id: Long,
    val title: String,
    val description: String,
    val code: String,
    val language: String
)

private class CodeStore(context: Context) {

    private val prefs =
        context.getSharedPreferences("my_coding_store", Context.MODE_PRIVATE)

    fun pin(): String =
        prefs.getString("pin", "123456") ?: "123456"

    fun setPin(value: String) {
        prefs.edit().putString("pin", value).apply()
    }

    fun loadCodes(): List<CodeItem> {
        val raw = prefs.getString("codes", "[]") ?: "[]"

        return runCatching {
            val array = JSONArray(raw)

            buildList {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)

                    add(
                        CodeItem(
                            id = o.optLong("id"),
                            title = o.optString("title"),
                            description = o.optString("description"),
                            code = o.optString("code"),
                            language = o.optString("language", "plaintext")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    fun saveCodes(items: List<CodeItem>) {

        val array = JSONArray()

        items.forEach { item ->

            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("title", item.title)
                    put("description", item.description)
                    put("code", item.code)
                    put("language", item.language)
                }
            )
        }

        prefs.edit()
            .putString("codes", array.toString())
            .apply()
    }

    fun exportAll(): String {

        return JSONObject().apply {
            put("format", "my_coding_backup")
            put("version", 1)
            put("pin", pin())
            put(
                "codes",
                JSONArray(
                    prefs.getString("codes", "[]") ?: "[]"
                )
            )
        }.toString(2)
    }

    fun importAll(json: String): Result<Unit> =
        runCatching {

            val root = JSONObject(json)

            require(
                root.optString("format") == "my_coding_backup"
            ) {
                "This is not a My Coding backup file."
            }

            val importedPin =
                root.optString("pin", "123456")

            require(
                importedPin.matches(
                    Regex("\\d{6}")
                )
            ) {
                "Backup contains an invalid PIN."
            }

            val codes =
                root.optJSONArray("codes")
                    ?: JSONArray()

            prefs.edit()
                .putString("pin", importedPin)
                .putString("codes", codes.toString())
                .apply()
        }
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MyCodingApp()
        }
    }
}

@Composable
private fun MyCodingApp() {

    val context = LocalContext.current
    val activity = context as Activity

    val store =
        remember {
            CodeStore(context.applicationContext)
        }

    var authenticated by
        remember { mutableStateOf(false) }

    var codes by
        remember {
            mutableStateOf(store.loadCodes())
        }

    var selectedTab by
        remember { mutableStateOf(0) }

    var editRequestId by
        remember { mutableStateOf<Long?>(null) }

    var refreshToken by
        remember { mutableIntStateOf(0) }

    val exportLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument(
                "application/json"
            )
        ) { uri ->

            if (uri != null) {

                runCatching {

                    context.contentResolver
                        .openOutputStream(uri)
                        ?.use { out ->

                            out.write(
                                store.exportAll()
                                    .toByteArray(
                                        Charsets.UTF_8
                                    )
                            )
                        }
                        ?: error(
                            "Could not open the selected file location."
                        )

                }.onFailure {

                    ToastLike.show(
                        context,
                        it.message ?: "Export failed."
                    )
                }
            }
        }

    val restoreLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->

            if (uri != null) {

                runCatching {

                    val text =
                        context.contentResolver
                            .openInputStream(uri)
                            ?.use {
                                it.readBytes()
                                    .toString(
                                        Charsets.UTF_8
                                    )
                            }
                            ?: error(
                                "Could not read the selected backup file."
                            )

                    store.importAll(text).getOrThrow()

                    codes = store.loadCodes()

                    refreshToken++

                    ToastLike.show(
                        context,
                        "All data restored successfully."
                    )

                }.onFailure {

                    ToastLike.show(
                        context,
                        it.message ?: "Restore failed."
                    )
                }
            }
        }

    if (!authenticated) {

        PinScreen(
            expectedPin = store.pin(),
            onSuccess = {
                authenticated = true
            },
            onExit = {
                activity.finishAndRemoveTask()
            }
        )

        return
    }

    BackHandler {
        selectedTab = 0
    }

    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Primary,
            onPrimary = Color.White,
            background = Canvas,
            surface = Card,
            onSurface = Ink,
            onBackground = Ink,
            secondary = Success,
            error = Danger
        )
    ) {

        Box(
            Modifier.fillMaxSize()
        ) {

            Image(
                painter =
                    painterResource(
                        id = R.drawable.background
                    ),
                contentDescription = null,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .alpha(0.035f),
                contentScale =
                    androidx.compose.ui.layout
                        .ContentScale.Crop
            )

            Scaffold(
                containerColor = Color.Transparent,

                topBar = {
                    Header(
                        onExit = {
                            activity.finishAndRemoveTask()
                        }
                    )
                },

                bottomBar = {
                    Footer(
                        selectedTab = selectedTab,
                        onTabSelected = {
                            selectedTab = it
                        }
                    )
                }

            ) { padding ->

                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(
                                Canvas.copy(
                                    alpha = 0.92f
                                )
                            )
                            .padding(padding)
                ) {

                    key(refreshToken) {

                        when (selectedTab) {

                            0 -> MyCodingPage(
                                codes = codes,

                                onReorder = { updated ->
                                    codes = updated
                                    store.saveCodes(updated)
                                },

                                onDelete = { id ->
                                    codes =
                                        codes.filterNot {
                                            it.id == id
                                        }

                                    store.saveCodes(codes)
                                },

                                onEdit = { id ->
                                    editRequestId = id
                                    selectedTab = 1
                                }
                            )

                            1 -> WriteCodePage(
                                codes = codes,
                                startEditId = editRequestId,

                                onEditRequestConsumed = {
                                    editRequestId = null
                                },

                                onSave = { item, editingId ->

                                    codes =
                                        if (editingId == null) {
                                            codes + item
                                        } else {
                                            codes.map {
                                                if (it.id == editingId)
                                                    item
                                                else
                                                    it
                                            }
                                        }

                                    store.saveCodes(codes)
                                },

                                onDelete = { id ->

                                    codes =
                                        codes.filterNot {
                                            it.id == id
                                        }

                                    store.saveCodes(codes)
                                }
                            )

                            2 -> BackupPage(
                                onExport = {
                                    exportLauncher.launch(
                                        "my_coding_backup.json"
                                    )
                                },

                                onRestore = {
                                    restoreLauncher.launch(
                                        arrayOf(
                                            "application/json",
                                            "text/plain",
                                            "*/*"
                                        )
                                    )
                                }
                            )

                            else -> ControlPanelPage(
                                currentPin = store.pin(),

                                onPinReset = { newPin ->
                                    store.setPin(newPin)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

private object ToastLike {

    fun show(
        context: Context,
        message: String
    ) {

        android.widget.Toast
            .makeText(
                context,
                message,
                android.widget.Toast.LENGTH_SHORT
            )
            .show()
    }
}

@Composable
private fun Header(
    onExit: () -> Unit
) {

    Surface(
        color = Primary,
        shadowElevation = 3.dp
    ) {

        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp),

            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Spacer(
                Modifier.weight(1f)
            )

            Text(
                "My Coding",
                color = Color.White,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                letterSpacing = 0.5.sp
            )

            Spacer(
                Modifier.weight(1f)
            )

            TextButton(
                onClick = onExit
            ) {

                Text(
                    "Exit",
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun Footer(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit
) {

    Surface(
        color = Primary,
        shadowElevation = 6.dp
    ) {

        Column {

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(64.dp)
                        .padding(horizontal = 6.dp),

                verticalAlignment =
                    Alignment.CenterVertically
            ) {

                listOf(
                    "My Coding",
                    "Write Code",
                    "Export/Restore All",
                    "Control Panel"
                ).forEachIndexed { index, label ->

                    val active =
                        selectedTab == index

                    Box(
                        modifier =
                            Modifier
                                .weight(1f)
                                .padding(
                                    horizontal = 2.dp,
                                    vertical = 6.dp
                                )
                                .fillMaxHeight()
                                .background(
                                    if (active)
                                        Color.White.copy(
                                            alpha = 0.18f
                                        )
                                    else
                                        Color.Transparent,
                                    RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    onTabSelected(index)
                                },

                        contentAlignment =
                            Alignment.Center
                    ) {

                        Text(
                            label,
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight =
                                if (active)
                                    FontWeight.Bold
                                else
                                    FontWeight.Medium,
                            maxLines = 2,
                            textAlign =
                                androidx.compose.ui.text.style
                                    .TextAlign.Center
                        )
                    }
                }
            }

            HorizontalDivider(
                color =
                    Color.White.copy(
                        alpha = 0.18f
                    )
            )

            Text(
                "@ 2026 Built & Developed by Sanat Dey",
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                color =
                    Color.White.copy(
                        alpha = 0.9f
                    ),
                fontSize = 11.sp,
                textAlign =
                    androidx.compose.ui.text.style
                        .TextAlign.Center
            )
        }
    }
}

@Composable
private fun PinScreen(
    expectedPin: String,
    onSuccess: () -> Unit,
    onExit: () -> Unit
) {

    var pin by
        remember { mutableStateOf("") }

    var error by
        remember { mutableStateOf<String?>(null) }

    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            Color(0xFFF7F8FD),
                            Color(0xFFEFF2FA)
                        )
                    )
                ),

        contentAlignment =
            Alignment.Center
    ) {

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(28.dp),

            shape =
                RoundedCornerShape(24.dp),

            elevation =
                CardDefaults.cardElevation(
                    8.dp
                )
        ) {

            Column(
                modifier =
                    Modifier.padding(24.dp),

                horizontalAlignment =
                    Alignment.CenterHorizontally
            ) {

                Text(
                    "MY CODING",
                    color = Primary,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp
                )

                Text(
                    "Enter your 6-digit PIN",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier =
                        Modifier.padding(top = 4.dp)
                )

                Spacer(
                    Modifier.height(18.dp)
                )

                OutlinedTextField(
                    value = pin,

                    onValueChange = {

                        if (
                            it.length <= 6 &&
                            it.all(Char::isDigit)
                        ) {
                            pin = it
                            error = null
                        }
                    },

                    singleLine = true,

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.NumberPassword
                        ),

                    visualTransformation =
                        PasswordVisualTransformation(),

                    label = {
                        Text("PIN")
                    }
                )

                error?.let {

                    Text(
                        it,
                        color = Danger,
                        fontSize = 12.sp,
                        modifier =
                            Modifier.padding(
                                top = 8.dp
                            )
                    )
                }

                Spacer(
                    Modifier.height(16.dp)
                )

                PremiumButton(
                    "Unlock",
                    enabled = pin.length == 6
                ) {

                    if (pin == expectedPin)
                        onSuccess()
                    else
                        error =
                            "Incorrect PIN. Please try again."
                }

                TextButton(
                    onClick = onExit,
                    modifier =
                        Modifier.padding(top = 4.dp)
                ) {

                    Text(
                        "Exit",
                        color = Muted
                    )
                }
            }
        }
    }
}

@Composable
private fun PageTitle(
    title: String,
    subtitle: String? = null
) {

    Column(
        modifier =
            Modifier.padding(
                horizontal = 18.dp,
                vertical = 12.dp
            )
    ) {

        Text(
            title,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = Ink
        )

        subtitle?.let {

            Text(
                it,
                fontSize = 13.sp,
                color = Muted,
                modifier =
                    Modifier.padding(
                        top = 3.dp
                    )
            )
        }
    }
}

@Composable
private fun MyCodingPage(
    codes: List<CodeItem>,
    onReorder: (List<CodeItem>) -> Unit,
    onDelete: (Long) -> Unit,
    onEdit: (Long) -> Unit
) {

    var expandedId by
        remember { mutableStateOf<Long?>(null) }

    val listState =
        rememberLazyListState()

    var draggingId by
        remember { mutableStateOf<Long?>(null) }

    var dragOffset by
        remember { mutableFloatStateOf(0f) }

    Column(
        Modifier.fillMaxSize()
    ) {

        PageTitle(
            "My Coding",
            "Your saved code library — long-press a title to reorder."
        )

        if (codes.isEmpty()) {

            EmptyCard(
                "No code saved yet",
                "Open Write Code and save your first snippet."
            )

        } else {

            LazyColumn(
                state = listState,

                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),

                contentPadding =
                    PaddingValues(
                        bottom = 12.dp
                    ),

                verticalArrangement =
                    Arrangement.spacedBy(9.dp)
            ) {

                items(
                    codes,
                    key = { it.id }
                ) { item ->

                    val latestCodes by
                        rememberUpdatedState(codes)

                    val dragging =
                        draggingId == item.id

                    Card(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .graphicsLayerCompat(
                                    translationY =
                                        if (dragging)
                                            dragOffset
                                        else
                                            0f
                                )
                                .pointerInput(item.id) {

                                    detectDragGesturesAfterLongPress(

                                        onDragStart = {
                                            draggingId = item.id
                                            dragOffset = 0f
                                        },

                                        onDragCancel = {
                                            draggingId = null
                                            dragOffset = 0f
                                        },

                                        onDragEnd = {
                                            draggingId = null
                                            dragOffset = 0f
                                        },

                                        onDrag = {
                                                _,
                                                amount ->


                                            dragOffset +=
                                                amount.y

                                            val current =
                                                listState
                                                    .layoutInfo
                                                    .visibleItemsInfo
                                                    .firstOrNull {
                                                        it.key ==
                                                            item.id
                                                    }

                                            if (current != null) {

                                                val center =
                                                    current.offset +
                                                        current.size / 2f +
                                                        dragOffset

                                                val target =
                                                    listState
                                                        .layoutInfo
                                                        .visibleItemsInfo
                                                        .filter {
                                                            it.key !=
                                                                item.id
                                                        }
                                                        .minByOrNull {
                                                            kotlin.math.abs(
                                                                (
                                                                    it.offset +
                                                                        it.size / 2f
                                                                ) - center
                                                            )
                                                        }

                                                if (
                                                    target != null &&
                                                    kotlin.math.abs(
                                                        (
                                                            target.offset +
                                                                target.size / 2f
                                                        ) - center
                                                    ) <
                                                    current.size * 0.55f
                                                ) {

                                                    val from =
                                                        latestCodes
                                                            .indexOfFirst {
                                                                it.id ==
                                                                    item.id
                                                            }

                                                    val to =
                                                        latestCodes
                                                            .indexOfFirst {
                                                                it.id ==
                                                                    target.key
                                                            }

                                                    if (
                                                        from >= 0 &&
                                                        to >= 0 &&
                                                        from != to
                                                    ) {

                                                        val mutable =
                                                            latestCodes
                                                                .toMutableList()

                                                        val moved =
                                                            mutable.removeAt(
                                                                from
                                                            )

                                                        mutable.add(
                                                            to,
                                                            moved
                                                        )

                                                        onReorder(
                                                            mutable
                                                        )

                                                        dragOffset = 0f
                                                    }
                                                }
                                            }
                                        }
                                    )
                                }
                                .clickable {

                                    expandedId =
                                        if (
                                            expandedId ==
                                                item.id
                                        )
                                            null
                                        else
                                            item.id
                                },

                        shape =
                            RoundedCornerShape(17.dp),

                        colors =
                            CardDefaults.cardColors(
                                containerColor = Card
                            ),

                        border =
                            androidx.compose.foundation
                                .BorderStroke(
                                    1.dp,
                                    Border
                                )
                    ) {

                        Column(
                            Modifier.padding(15.dp)
                        ) {

                            Text(
                                item.title,
                                fontWeight =
                                    FontWeight.SemiBold,
                                fontSize = 16.sp,
                                color = Ink
                            )

                            if (
                                expandedId ==
                                    item.id
                            ) {

                                Spacer(
                                    Modifier.height(12.dp)
                                )

                                Text(
                                    "Description",
                                    fontSize = 12.sp,
                                    fontWeight =
                                        FontWeight.Bold,
                                    color = Primary
                                )

                                SelectionContainer {

                                    Text(
                                        item.description
                                            .ifBlank {
                                                "No description provided."
                                            },

                                        fontSize = 14.sp,
                                        color = Ink,

                                        modifier =
                                            Modifier.padding(
                                                top = 4.dp
                                            )
                                    )
                                }

                                Spacer(
                                    Modifier.height(12.dp)
                                )

                                Text(
                                    "Original Code",
                                    fontSize = 12.sp,
                                    fontWeight =
                                        FontWeight.Bold,
                                    color = Primary
                                )

                                CodeViewer(
                                    item.code,
                                    item.language
                                )

                                Row(
                                    horizontalArrangement =
                                        Arrangement.spacedBy(8.dp),

                                    modifier =
                                        Modifier.padding(
                                            top = 12.dp
                                        )
                                ) {

                                    OutlinedButton(
                                        onClick = {
                                            onEdit(
                                                item.id
                                            )
                                        }
                                    ) {
                                        Text("Edit")
                                    }

                                    Button(
                                        onClick = {
                                            onDelete(
                                                item.id
                                            )
                                        },

                                        colors =
                                            ButtonDefaults
                                                .buttonColors(
                                                    containerColor =
                                                        Danger
                                                )
                                    ) {
                                        Text("Delete")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.graphicsLayerCompat(
    translationY: Float
): Modifier =
    this.then(
        Modifier.graphicsLayer {
            this.translationY =
                translationY
        }
    )

@Composable
private fun WriteCodePage(
    codes: List<CodeItem>,
    startEditId: Long?,
    onEditRequestConsumed: () -> Unit,
    onSave: (CodeItem, Long?) -> Unit,
    onDelete: (Long) -> Unit
) {

    var writing by
        remember { mutableStateOf(false) }

    var editingId by
        remember { mutableStateOf<Long?>(null) }

    var title by
        remember { mutableStateOf("") }

    var description by
        remember { mutableStateOf("") }

    var code by
        remember { mutableStateOf("") }

    var language by
        remember { mutableStateOf("python") }

    val scroll =
        rememberScrollState()

    LaunchedEffect(startEditId) {

        val requested =
            startEditId
                ?: return@LaunchedEffect

        codes
            .firstOrNull {
                it.id == requested
            }
            ?.let { item ->

                editingId = item.id
                title = item.title
                description = item.description
                code = item.code
                language = item.language
                writing = true
            }

        onEditRequestConsumed()
    }

    fun startNew() {

        editingId = null
        title = ""
        description = ""
        code = ""
        language = "python"
        writing = true
    }

    fun startEdit(
        item: CodeItem
    ) {

        editingId = item.id
        title = item.title
        description = item.description
        code = item.code
        language = item.language
        writing = true
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
    ) {

        PageTitle(
            "Write Code",
            "Create, edit and delete your personal code snippets."
        )

        if (!writing) {

            PremiumButton(
                "Write Code"
            ) {
                startNew()
            }

            Spacer(
                Modifier.height(10.dp)
            )

            codes.forEach { item ->

                CodeManagementRow(
                    item,
                    onEdit = {
                        startEdit(item)
                    },
                    onDelete = {
                        onDelete(item.id)
                    }
                )
            }

            if (codes.isEmpty()) {

                EmptyCard(
                    "Your code list is empty",
                    "Tap Write Code to begin."
                )
            }

        } else {

            EditorCard(
                title = title,
                onTitleChange = {
                    title = it
                },

                description = description,
                onDescriptionChange = {
                    description = it
                },

                code = code,
                onCodeChange = {
                    code = it
                },

                language = language,
                onLanguageChange = {
                    language = it
                }
            )

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = 18.dp,
                            vertical = 10.dp
                        ),

                horizontalArrangement =
                    Arrangement.spacedBy(10.dp)
            ) {

                PremiumButton(
                    "Save Code",
                    Modifier.weight(1f)
                ) {

                    val finalTitle =
                        title
                            .trim()
                            .ifBlank {
                                titleFromDescription(
                                    description
                                )
                            }

                    onSave(

                        CodeItem(
                            id =
                                editingId
                                    ?: System.currentTimeMillis(),

                            title = finalTitle,

                            description =
                                description.trim(),

                            code = code,

                            language = language
                        ),

                        editingId
                    )

                    writing = false
                }

                OutlinedButton(
                    onClick = {
                        writing = false
                    },

                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun CodeManagementRow(
    item: CodeItem,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 18.dp,
                    vertical = 4.dp
                ),

        shape =
            RoundedCornerShape(15.dp),

        border =
            androidx.compose.foundation
                .BorderStroke(
                    1.dp,
                    Border
                )
    ) {

        Row(
            Modifier.padding(13.dp),
            verticalAlignment =
                Alignment.CenterVertically
        ) {

            Column(
                Modifier.weight(1f)
            ) {

                Text(
                    item.title,
                    fontWeight =
                        FontWeight.SemiBold,
                    fontSize = 15.sp
                )

                Text(
                    item.language.uppercase(
                        Locale.getDefault()
                    ),

                    color = Primary,
                    fontSize = 10.sp,
                    fontWeight =
                        FontWeight.Bold
                )
            }

            TextButton(
                onClick = onEdit
            ) {
                Text("Edit")
            }

            TextButton(
                onClick = onDelete
            ) {
                Text(
                    "Delete",
                    color = Danger
                )
            }
        }
    }
}

@Composable
private fun EditorCard(
    title: String,
    onTitleChange: (String) -> Unit,
    description: String,
    onDescriptionChange: (String) -> Unit,
    code: String,
    onCodeChange: (String) -> Unit,
    language: String,
    onLanguageChange: (String) -> Unit
) {

    val languages =
        listOf(
            "python",
            "html",
            "css",
            "javascript",
            "java",
            "kotlin",
            "c",
            "cpp",
            "sql",
            "json",
            "xml",
            "bash",
            "plaintext"
        )

    Column(
        Modifier.padding(
            horizontal = 18.dp
        )
    ) {

        FieldLabel(
            "Code Title"
        )

        OutlinedTextField(
            value = title,
            onValueChange = onTitleChange,
            modifier =
                Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = {
                Text("Title (optional)")
            }
        )

        FieldLabel(
            "Code Description"
        )

        OutlinedTextField(
            value = description,
            onValueChange = onDescriptionChange,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(
                        min = 90.dp
                    ),
            minLines = 3,
            placeholder = {
                Text(
                    "What does this code do?"
                )
            }
        )

        FieldLabel(
            "Coding Language"
        )

        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(
                    rememberScrollState()
                ),

            horizontalArrangement =
                Arrangement.spacedBy(6.dp)
        ) {

            languages.forEach { lang ->

                FilterChip(
                    selected =
                        language == lang,

                    onClick = {
                        onLanguageChange(
                            lang
                        )
                    },

                    label = {
                        Text(lang)
                    }
                )
            }
        }

        FieldLabel(
            "Original Code"
        )

        CodeEditor(
            code = code,
            language = language,
            onCodeChange = onCodeChange
        )
    }
}

@Composable
private fun FieldLabel(
    text: String
) {

    Text(
        text,
        color = Primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,

        modifier =
            Modifier.padding(
                top = 13.dp,
                bottom = 6.dp
            )
    )
}

@Composable
private fun CodeEditor(
    code: String,
    language: String,
    onCodeChange: (String) -> Unit
) {

    val horizontal =
        rememberScrollState()

    val vertical =
        rememberScrollState()

    val transformed =
        SyntaxVisualTransformation(
            language
        )

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .border(
                    1.dp,
                    Border,
                    RoundedCornerShape(15.dp)
                )
                .background(
                    Color(0xFF101828),
                    RoundedCornerShape(15.dp)
                )
                .padding(13.dp)
                .horizontalScroll(
                    horizontal
                )
                .verticalScroll(
                    vertical
                )
    ) {

        BasicTextField(
            value = code,
            onValueChange = { newCode: String ->
                onCodeChange(
                    autoIndent(
                        code,
                        newCode,
                        language
                    )
                )
            },
            modifier = Modifier.widthIn(
                min = 700.dp
            ),
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color(0xFFE6EAF2),
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                lineHeight = 19.sp
            ),
            visualTransformation = transformed,
            singleLine = false,
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                Color.White
            ),
            decorationBox = { innerTextField ->
                if (code.isEmpty()) {
                    Text(
                        "Write your ${language.uppercase(Locale.getDefault())} code here...",
                        color = Color(0xFF98A2B3),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                }
        
                innerTextField()
            }
        )
    }
}


@Composable
private fun CodeViewer(
    code: String,
    language: String
) {

    val horizontal =
        rememberScrollState()

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFF101828)
            ),

        shape =
            RoundedCornerShape(14.dp)
    ) {

        SelectionContainer {

            Text(
                SyntaxHighlighter.highlight(
                    code,
                    language
                ),

                modifier =
                    Modifier
                        .widthIn(
                            min = 700.dp
                        )
                        .horizontalScroll(
                            horizontal
                        )
                        .padding(13.dp),

                fontFamily =
                    FontFamily.Monospace,

                fontSize = 12.sp,

                lineHeight = 18.sp
            )
        }
    }
}

private class SyntaxVisualTransformation(
    private val language: String
) : VisualTransformation {

    override fun filter(
        text: AnnotatedString
    ): TransformedText {

        return TransformedText(
            SyntaxHighlighter.highlight(
                text.text,
                language
            ),

            OffsetMapping.Identity
        )
    }
}

private object SyntaxHighlighter {

    private val keywordMap =
        mapOf(

            "python" to
                "and|as|assert|async|await|break|class|continue|def|del|elif|else|except|False|finally|for|from|global|if|import|in|is|lambda|None|nonlocal|not|or|pass|raise|return|True|try|while|with|yield",

            "java" to
                "abstract|boolean|break|byte|case|catch|char|class|const|continue|default|do|double|else|enum|extends|final|finally|float|for|if|implements|import|instanceof|int|interface|long|new|null|package|private|protected|public|return|short|static|super|switch|this|throw|throws|try|void|volatile|while|true|false",

            "kotlin" to
                "as|break|class|continue|do|else|false|for|fun|if|in|interface|is|null|object|package|private|protected|public|return|super|this|throw|true|try|typealias|val|var|when|while",

            "javascript" to
                "break|case|catch|class|const|continue|debugger|default|delete|do|else|export|extends|false|finally|for|function|if|import|in|instanceof|let|new|null|return|static|super|switch|this|throw|true|try|typeof|var|void|while|with|yield|async|await",

            "css" to
                "important|inherit|initial|unset|none|block|inline|flex|grid|relative|absolute|fixed|sticky",

            "c" to
                "auto|break|case|char|const|continue|default|do|double|else|enum|extern|float|for|goto|if|int|long|register|return|short|signed|sizeof|static|struct|switch|typedef|union|unsigned|void|volatile|while",

            "cpp" to
                "alignas|alignof|auto|bool|break|case|catch|char|class|const|constexpr|continue|default|delete|do|double|else|enum|explicit|export|extern|false|float|for|friend|if|inline|int|long|namespace|new|nullptr|operator|private|protected|public|return|short|signed|sizeof|static|struct|switch|template|this|throw|true|try|typedef|typename|union|unsigned|using|virtual|void|volatile|while",

            "sql" to
                "SELECT|FROM|WHERE|INSERT|INTO|VALUES|UPDATE|SET|DELETE|CREATE|TABLE|ALTER|DROP|JOIN|LEFT|RIGHT|INNER|OUTER|GROUP|BY|ORDER|HAVING|LIMIT|AS|AND|OR|NOT|NULL|PRIMARY|KEY"
        )

    fun highlight(
        code: String,
        language: String
    ): AnnotatedString {

        val builder =
            AnnotatedString.Builder()

        builder.append(code)

        if (code.isEmpty())
            return builder.toAnnotatedString()

        val kw =
            keywordMap[
                language.lowercase(
                    Locale.getDefault()
                )
            ]

        if (kw != null) {

            Regex(
                "\\b(?:$kw)\\b",
                RegexOption.IGNORE_CASE
            )
                .findAll(code)
                .forEach { m ->

                    builder.addStyle(
                        SpanStyle(
                            color =
                                Color(0xFF82AAFF),
                            fontWeight =
                                FontWeight.Bold
                        ),

                        m.range.first,
                        m.range.last + 1
                    )
                }
        }

        Regex(
            "(#.*$|//.*$|/\\*[\\s\\S]*?\\*/|<!--.*?-->)",
            setOf(
                RegexOption.MULTILINE
            )
        )
            .findAll(code)
            .forEach { m ->

                builder.addStyle(
                    SpanStyle(
                        color =
                            Color(0xFF98A2B3),
                        fontStyle =
                            FontStyle.Italic
                    ),

                    m.range.first,
                    m.range.last + 1
                )
            }

        Regex(
            "(\\\"(?:\\\\.|[^\\\"\\\\])*\\\"|'(?:\\\\.|[^'\\\\])*'|`(?:\\\\.|[^`\\\\])*`)"
        )
            .findAll(code)
            .forEach { m ->

                builder.addStyle(
                    SpanStyle(
                        color =
                            Color(0xFF8DD39E)
                    ),

                    m.range.first,
                    m.range.last + 1
                )
            }

        Regex(
            "\\b\\d+(?:\\.\\d+)?\\b"
        )
            .findAll(code)
            .forEach { m ->

                builder.addStyle(
                    SpanStyle(
                        color =
                            Color(0xFFC792EA)
                    ),

                    m.range.first,
                    m.range.last + 1
                )
            }

        if (
            language == "html" ||
            language == "xml"
        ) {

            Regex(
                "</?[A-Za-z][^>]*>"
            )
                .findAll(code)
                .forEach { m ->

                    builder.addStyle(
                        SpanStyle(
                            color =
                                Color(0xFFFFCB6B)
                        ),

                        m.range.first,
                        m.range.last + 1
                    )
                }
        }

        return builder.toAnnotatedString()
    }
}

private fun autoIndent(
    old: String,
    new: String,
    language: String
): String {

    if (
        new.length <= old.length ||
        !new.endsWith("\n")
    )
        return new

    val before =
        new.dropLast(1)

    val lastLine =
        before.substringAfterLast('\n')

    val base =
        lastLine.takeWhile {
            it == ' ' || it == '\t'
        }

    val trimmed =
        lastLine.trimEnd()

    val add =
        when {

            language in
                listOf(
                    "python",
                    "yaml"
                ) &&
                trimmed.endsWith(":") ->
                "    "

            language in
                listOf(
                    "javascript",
                    "java",
                    "kotlin",
                    "c",
                    "cpp",
                    "css",
                    "json"
                ) &&
                (
                    trimmed.endsWith("{") ||
                    trimmed.endsWith("(")
                ) ->
                "    "

            language in
                listOf(
                    "html",
                    "xml"
                ) &&
                trimmed.matches(
                    Regex(
                        "<[^/!][^>]*>"
                    )
                ) &&
                !trimmed.contains("</") ->
                "    "

            else ->
                ""
        }

    return new + base + add
}

private fun titleFromDescription(
    description: String
): String {

    val words =
        description
            .trim()
            .split(
                Regex("\\s+")
            )
            .filter {
                it.isNotBlank()
            }

    return if (words.isEmpty()) {

        "Untitled Code"

    } else {

        words
            .take(6)
            .joinToString(" ")
            .trim()
            .let {
                if (it.length > 60)
                    it.take(60)
                else
                    it
            }
    }
}

@Composable
private fun BackupPage(
    onExport: () -> Unit,
    onRestore: () -> Unit
) {

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
    ) {

        PageTitle(
            "Export / Restore All",
            "Use Android's official file manager. No storage permission is required."
        )

        ActionCard(
            "Export All Data",
            "Save your PIN, code list, descriptions, source code and ordering into one backup file."
        ) {
            onExport()
        }

        ActionCard(
            "Restore All Data",
            "Choose a previously exported My Coding backup from any available drive or provider."
        ) {
            onRestore()
        }

        InfoCard(
            "Backup format",
            "The app uses a small JSON backup. Restore replaces the current app data with the selected backup."
        )
    }
}

@Composable
private fun ControlPanelPage(
    currentPin: String,
    onPinReset: (String) -> Unit
) {

    var current by
        remember { mutableStateOf("") }

    var next by
        remember { mutableStateOf("") }

    var message by
        remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(
                rememberScrollState()
            )
    ) {

        PageTitle(
            "Control Panel",
            "Manage your application access PIN."
        )

        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp),

            shape =
                RoundedCornerShape(18.dp),

            border =
                androidx.compose.foundation
                    .BorderStroke(
                        1.dp,
                        Border
                    )
        ) {

            Column(
                Modifier.padding(18.dp)
            ) {

                Text(
                    "Reset Your App PIN",
                    fontSize = 17.sp,
                    fontWeight =
                        FontWeight.Bold
                )

                Text(
                    "Your new PIN will be required the next time the app opens.",
                    color = Muted,
                    fontSize = 13.sp,

                    modifier =
                        Modifier.padding(
                            top = 4.dp
                        )
                )

                Spacer(
                    Modifier.height(12.dp)
                )

                OutlinedTextField(
                    value = current,

                    onValueChange = {
                        if (
                            it.length <= 6 &&
                            it.all(Char::isDigit)
                        )
                            current = it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    singleLine = true,

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.NumberPassword
                        ),

                    visualTransformation =
                        PasswordVisualTransformation(),

                    label = {
                        Text("Current PIN")
                    }
                )

                Spacer(
                    Modifier.height(9.dp)
                )

                OutlinedTextField(
                    value = next,

                    onValueChange = {
                        if (
                            it.length <= 6 &&
                            it.all(Char::isDigit)
                        )
                            next = it
                    },

                    modifier =
                        Modifier.fillMaxWidth(),

                    singleLine = true,

                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType =
                                KeyboardType.NumberPassword
                        ),

                    visualTransformation =
                        PasswordVisualTransformation(),

                    label = {
                        Text("Enter new PIN")
                    }
                )

                message?.let {

                    Text(
                        it,

                        color =
                            if (
                                it.startsWith(
                                    "PIN reset"
                                )
                            )
                                Success
                            else
                                Danger,

                        fontSize = 12.sp,

                        modifier =
                            Modifier.padding(
                                top = 9.dp
                            )
                    )
                }

                Spacer(
                    Modifier.height(12.dp)
                )

                PremiumButton(
                    "Reset PIN",

                    enabled =
                        current.length == 6 &&
                        next.length == 6
                ) {

                    if (current == currentPin) {

                        onPinReset(next)

                        current = ""
                        next = ""

                        message =
                            "PIN reset successfully."

                    } else {

                        message =
                            "Current PIN is incorrect."
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    title: String,
    description: String,
    onClick: () -> Unit
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = 18.dp,
                    vertical = 5.dp
                ),

        shape =
            RoundedCornerShape(18.dp),

        border =
            androidx.compose.foundation
                .BorderStroke(
                    1.dp,
                    Border
                )
    ) {

        Column(
            Modifier.padding(16.dp)
        ) {

            Text(
                title,
                fontWeight =
                    FontWeight.Bold,
                fontSize = 16.sp
            )

            Text(
                description,
                color = Muted,
                fontSize = 13.sp,

                modifier =
                    Modifier.padding(
                        top = 4.dp
                    )
            )

            PremiumButton(
                title,

                Modifier
                    .fillMaxWidth()
                    .padding(
                        top = 12.dp
                    ),

                onClick = onClick
            )
        }
    }
}

@Composable
private fun InfoCard(
    title: String,
    body: String
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(18.dp),

        shape =
            RoundedCornerShape(16.dp),

        colors =
            CardDefaults.cardColors(
                containerColor =
                    Color(0xFFEFF2FF)
            )
    ) {

        Column(
            Modifier.padding(15.dp)
        ) {

            Text(
                title,
                color = Primary,
                fontWeight =
                    FontWeight.Bold,
                fontSize = 14.sp
            )

            Text(
                body,
                color = Ink,
                fontSize = 13.sp,

                modifier =
                    Modifier.padding(
                        top = 4.dp
                    )
            )
        }
    }
}

@Composable
private fun EmptyCard(
    title: String,
    body: String
) {

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(18.dp),

        shape =
            RoundedCornerShape(18.dp),

        border =
            androidx.compose.foundation
                .BorderStroke(
                    1.dp,
                    Border
                )
    ) {

        Column(
            Modifier
                .fillMaxWidth()
                .padding(24.dp),

            horizontalAlignment =
                Alignment.CenterHorizontally
        ) {

            Text(
                title,
                fontWeight =
                    FontWeight.Bold,
                fontSize = 16.sp
            )

            Text(
                body,
                color = Muted,
                fontSize = 13.sp,

                modifier =
                    Modifier.padding(
                        top = 4.dp
                    ),

                textAlign =
                    androidx.compose.ui.text.style
                        .TextAlign.Center
            )
        }
    }
}

@Composable
private fun PremiumButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {

    Button(
        onClick = onClick,
        enabled = enabled,

        modifier =
            modifier.heightIn(
                min = 46.dp
            ),

        shape =
            RoundedCornerShape(13.dp),

        colors =
            ButtonDefaults.buttonColors(
                containerColor = Primary,
                disabledContainerColor =
                    Primary.copy(
                        alpha = 0.35f
                    )
            ),

        elevation =
            ButtonDefaults.buttonElevation(
                defaultElevation = 2.dp,
                pressedElevation = 0.dp
            )
    ) {

        Box(
            contentAlignment =
                Alignment.Center
        ) {

            Image(
                painter =
                    painterResource(
                        id = R.drawable.button
                    ),

                contentDescription = null,

                modifier =
                    Modifier
                        .matchParentSize()
                        .alpha(
                            if (enabled)
                                0.08f
                            else
                                0.02f
                        ),

                contentScale =
                    androidx.compose.ui.layout
                        .ContentScale.Crop
            )

            Text(
                text,
                fontWeight =
                    FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
    }
}
