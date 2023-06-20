import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.window.FrameWindowScope
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.*

@Composable
fun FrameWindowScope.DragDrop(
    onDragStateChange: (Boolean) -> Unit = {},
    onDropped: (Any) -> Unit = {}
) {
    LaunchedEffect(Unit) {
        window.dropTarget = DropTarget().apply {
            addDropTargetListener(
                object : DropTargetAdapter() {
                    override fun dragEnter(dtde: DropTargetDragEvent?) {
                        super.dragEnter(dtde)
                        onDragStateChange(true)
                    }

                    override fun drop(event: DropTargetDropEvent) {
                        event.acceptDrop(DnDConstants.ACTION_COPY)
                        val draggedFileName = event.transferable.getTransferData(DataFlavor.javaFileListFlavor)
                        onDropped(draggedFileName)
                        event.dropComplete(true)
                        onDragStateChange(false)
                    }

                    override fun dragExit(dte: DropTargetEvent?) {
                        super.dragExit(dte)
                        onDragStateChange(false)
                    }
                }
            )
        }
    }
}