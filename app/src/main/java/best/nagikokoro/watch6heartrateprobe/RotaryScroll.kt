package best.nagikokoro.watch6heartrateprobe

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import kotlinx.coroutines.launch

@Composable
internal fun Modifier.rotaryBezelScroll(scrollState: ScrollState): Modifier {
    val focusRequester = remember { FocusRequester() }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(focusRequester) {
        focusRequester.requestFocus()
    }

    return onRotaryScrollEvent { event ->
        coroutineScope.launch {
            scrollState.scrollBy(event.verticalScrollPixels)
        }
        true
    }
        .focusRequester(focusRequester)
        .focusable()
}
