package com.programmersbox.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import korlibs.io.async.launch
import korlibs.io.file.*
import korlibs.io.file.std.localVfs
import moe.tlaster.precompose.viewmodel.ViewModel
import moe.tlaster.precompose.viewmodel.viewModel
import moe.tlaster.precompose.viewmodel.viewModelScope

@Composable
internal fun App() {
    Surface {
        val vm = viewModel(GameBoyViewModel::class) { GameBoyViewModel() }

        GameBoyScreen(vm)
    }
}

internal class GameBoyViewModel : ViewModel() {

    var pathName: VfsFile? = null

}