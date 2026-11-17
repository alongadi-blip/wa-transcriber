package com.alon.transcriber

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.mutableStateListOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

sealed interface ItemState {
    data object Loading : ItemState
    data class Done(val text: String) : ItemState
    data class Failed(val message: String) : ItemState
}

data class Item(val id: Long, val uri: Uri, val state: ItemState)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val prefs = Prefs(app)
    val items = mutableStateListOf<Item>()
    private var nextId = 0L

    fun submit(uris: List<Uri>) {
        if (uris.isEmpty()) return
        items.clear()
        uris.forEach { uri ->
            val item = Item(nextId++, uri, ItemState.Loading)
            items.add(item)
            run(item)
        }
    }

    fun retry(id: Long) {
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val item = items[idx].copy(state = ItemState.Loading)
        items[idx] = item
        run(item)
    }

    fun clear() = items.clear()

    private fun run(item: Item) {
        viewModelScope.launch {
            val result = try {
                val key = prefs.apiKey
                if (key.isBlank()) ItemState.Failed("צריך להגדיר מפתח API של Groq")
                else ItemState.Done(
                    GroqClient.transcribe(getApplication(), item.uri, key, prefs.language)
                )
            } catch (e: TranscribeException) {
                ItemState.Failed(e.message ?: "שגיאה לא ידועה")
            } catch (e: Exception) {
                ItemState.Failed("שגיאה: ${e.message ?: e.javaClass.simpleName}")
            }
            val idx = items.indexOfFirst { it.id == item.id }
            if (idx >= 0) items[idx] = items[idx].copy(state = result)
        }
    }
}
