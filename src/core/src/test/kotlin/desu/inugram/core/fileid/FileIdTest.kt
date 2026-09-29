package desu.inugram.core.fileid

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FileIdTest {
    @Test
    fun parses_known_file_ids() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.location, parseFileId(vector.fileId))
        }
    }

    @Test
    fun serializes_known_file_ids() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.reserialized, serializeFileId(vector.location))
        }
    }

    @Test
    fun reserialized_file_ids_parse_back() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.location, parseFileId(vector.reserialized))
        }
    }

    @Test
    fun serializes_known_unique_file_ids() {
        for (vector in ALL_VECTORS) {
            assertEquals(vector.fileId, vector.uniqueFileId, serializeUniqueFileId(vector.location))
        }
    }

    @Test
    fun parses_unique_ids_for_documents() {
        assertEquals(
            ParsedUniqueFileId.Document(1282363671355326586L),
            parseUniqueFileId("AgADegAD997LEQ"),
        )
        assertEquals(
            ParsedUniqueFileId.Document(5213102278772264052L),
            parseUniqueFileId("AgADdAwAAueoWEg"),
        )
        assertEquals(
            ParsedUniqueFileId.Document(541175087705905756L),
            parseUniqueFileId("AgADXFoAAuCjggc"),
        )
    }

    @Test
    fun parses_unique_ids_for_thumbnails() {
        assertEquals(
            ParsedUniqueFileId.Photo(UniquePhotoLocation.Id(5213102278772264052L, 114)),
            parseUniqueFileId("AQADdAwAAueoWEhy"),
        )
    }

    @Test
    fun parses_unique_ids_for_profile_pictures() {
        assertEquals(
            ParsedUniqueFileId.Photo(UniquePhotoLocation.VolumeId(247538121L, 338431)),
            parseUniqueFileId("AQADySHBDgAE_ykFAAE"),
        )
        assertEquals(
            ParsedUniqueFileId.Photo(UniquePhotoLocation.VolumeId(247538121L, 338429)),
            parseUniqueFileId("AQADySHBDgAE_SkFAAE"),
        )
    }

    @Test
    fun rejects_unsupported_version() {
        assertThrows(UnsupportedFileIdException::class.java) {
            parseFileId("CAADAQADegAD997LEUiQZafDlhIeAQ")
        }
    }

    @Test
    fun rejects_truncated_file_id() {
        assertThrows(FileIdException::class.java) {
            parseFileId(byteArrayOf(PERSISTENT_ID_VERSION_OLD.toByte()))
        }
    }
}
