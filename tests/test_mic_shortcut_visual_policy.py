import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
JAVA = ROOT / "android/app/src/main/java/io/github/amxooo/voiceinput2pc"

class MicShortcutVisualPolicyTest(unittest.TestCase):
    def test_state_mapping_and_window_bounded_menu_geometry(self):
        with tempfile.TemporaryDirectory() as out:
            subprocess.run(["javac", "-d", out, str(JAVA / "InputSession.java"),
                str(JAVA / "MicStatusVisualPolicy.java"), str(JAVA / "MicShortcutMenuGeometry.java"),
                str(ROOT / "tests/MicShortcutVisualPolicyTest.java")], check=True)
            result = subprocess.run(["java", "-cp", out, "MicShortcutVisualPolicyTest"],
                check=True, capture_output=True, text=True)
            self.assertIn("mic shortcut visual checks passed", result.stdout)

    def test_service_wires_the_approved_gesture_and_menu(self):
        source = (JAVA / "UniversalInputMethodService.java").read_text()
        self.assertIn("dockLongPressed && !dockGestureBlocked && !busy()", source)
        self.assertIn("MicShortcutMenuGeometry.layout", source)
        self.assertIn("p.micCenterX,p.micCenterY,dp(64),dp(48),dp(8)", source)
        self.assertIn("setIconSizeDp(18f)", source)
        self.assertIn("view.setLeafRing(true)", source)
        self.assertIn("LeafIconStyle.drawRing(canvas,paint,color", source)
        self.assertNotIn("0x55f5fafc", source)
        self.assertIn('menuShortcut("device"', source)
        self.assertIn('menuShortcut("history"', source)
        self.assertIn('menuShortcut("settings"', source)
        self.assertIn('menuShortcut("switch"', source)
        hub = source[source.index("private void renderCompactHub()") : source.index("private ImeIconButton hubIcon", source.index("private void renderCompactHub()"))]
        self.assertNotIn('"stats"', hub)
        self.assertNotIn('"keyboard"', hub)
        self.assertNotIn('"local"', hub)
        self.assertNotIn('"close"', hub)

if __name__ == "__main__":
    unittest.main()
