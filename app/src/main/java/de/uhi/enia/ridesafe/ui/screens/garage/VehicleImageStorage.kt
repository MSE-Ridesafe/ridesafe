package de.uhi.enia.ridesafe.ui.screens.garage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import de.uhi.enia.ridesafe.data.Vehicle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.max

private const val VEHICLE_IMAGE_MAX_EDGE = 1_600

internal fun vehicleImageFile(
    context: Context,
    vehicle: Vehicle,
): File {
    val directory = File(context.filesDir, "vehicle_images")
    return File(directory, "${vehicle.vehicleUuid.lowercase()}.webp")
}

internal suspend fun storeVehicleImage(
    context: Context,
    vehicle: Vehicle,
    sourceUri: Uri,
) = withContext(Dispatchers.IO) {
    val destination = vehicleImageFile(context, vehicle)
    destination.parentFile?.mkdirs()
    val temporary = File(destination.parentFile, ".vehicle_image_${UUID.randomUUID()}.tmp")
    try {
        val source = ImageDecoder.createSource(context.contentResolver, sourceUri)
        val bitmap =
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val width = info.size.width
                val height = info.size.height
                val scale = max(width, height).toFloat() / VEHICLE_IMAGE_MAX_EDGE
                if (scale > 1f) {
                    decoder.setTargetSize((width / scale).toInt(), (height / scale).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        FileOutputStream(temporary).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 88, output))
            output.fd.sync()
        }
        bitmap.recycle()
        try {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                destination.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    } finally {
        temporary.delete()
    }
}

internal fun loadVehicleImage(
    context: Context,
    vehicle: Vehicle,
): Bitmap? = BitmapFactory.decodeFile(vehicleImageFile(context, vehicle).path)

internal fun deleteVehicleImage(
    context: Context,
    vehicle: Vehicle,
) {
    vehicleImageFile(context, vehicle).delete()
}
