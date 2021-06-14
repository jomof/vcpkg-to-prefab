import org.junit.Test

class MainKtTest {
    @Test
    fun local() {
        main(arrayOf(
            "/Users/jomof/AndroidStudioProjects/vcpkg/packages",
            "com.github.jomof.prefab",
            "16",
            "21",
            "20",
            "c++_static",
        ))
    }
}