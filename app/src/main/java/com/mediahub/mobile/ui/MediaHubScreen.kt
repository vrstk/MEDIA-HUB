package com.mediahub.mobile.ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class Screen(val title: String, val symbol: String) {
    HOME("Главная", "⌂"),
    LIBRARY("Медиатека", "▦"),
    FILES("Файлы", "▤"),
    TASKS("Задачи", "✓")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MediaHubScreen(darkTheme: Boolean, onThemeToggle: () -> Unit) {
    var selected by rememberSaveable { mutableIntStateOf(0) }
    val current = Screen.entries[selected]
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Media Hub", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = onThemeToggle) {
                        Text(if (darkTheme) "Светлая" else "Тёмная")
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Screen.entries.forEachIndexed { index, screen ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Text(screen.symbol) },
                        label = { Text(screen.title) }
                    )
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding)
                .verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(current.title, style = MaterialTheme.typography.headlineMedium)
            when (current) {
                Screen.HOME -> {
                    InfoCard("Добро пожаловать", "Медиафайлы и облака в одном приложении. Это первая техническая версия.")
                    InfoCard("Локальные файлы", "Откройте вкладку «Файлы», чтобы выбрать папку Android и работать с ней без доступа ко всему накопителю.")
                    Button(onClick = { selected = Screen.FILES.ordinal }, modifier = Modifier.fillMaxWidth()) {
                        Text("Открыть файловый менеджер")
                    }
                    InfoCard("Google Drive", "Подключение OAuth и операции с файлами находятся в разработке. Учётные данные не запрашиваются.")
                    InfoCard("Облако Mail", "Подключение по WebDAV находится в разработке. Не вводите пароль в эту версию.")
                }
                Screen.LIBRARY -> {
                    InfoCard("Каталог", "Подготовлен отдельный архивный каталог: 22 739 профилей, 1 187 категорий и тегов. Импорт в приложение пока не реализован.")
                    InfoCard("Библиотеки", "Модели, личные фотографии и другие коллекции будут разделены. Фотографии из каталога не загружены.")
                    InfoCard("Умная группировка", "Анализ сходства лиц будет доступен только после включения пользователем. Сейчас он отключён.")
                }
                Screen.FILES -> LocalFilesPanel()
                Screen.TASKS -> {
                    InfoCard("Передачи", "Активных передач нет. Синхронизация и очередь ещё не подключены.")
                    InfoCard("Защита исходников", "Копирование не удаляет оригиналы. Перемещение потребует проверку результата и отдельное согласие.")
                }
            }
        }
    }
}

@Composable
internal fun InfoCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
