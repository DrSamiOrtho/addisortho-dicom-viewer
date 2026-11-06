# Addis Ortho shortcuts (6 Nov 2026)

One set of keys for the 2D viewer, the 3D view and both templating pages (Full and Quick). The standard is
RadiAnt DICOM Viewer's published list: https://www.radiantviewer.com/dicom-viewer-manual/keyboard_shortcuts.html
On a Mac the Cmd key works wherever this list says Ctrl. Change a key in one place only by changing it in all of them:
`weasis-core/.../ShortcutManager.java` and `ActionW.java` (2D), `viewer3d/.../Shortcuts3d.java` (3D), and in the templating
project `web/uikit.js` (Full) and `web-quick/quick.js` (Quick). A dash means the tool does not exist there.

| Key | Action | 2D viewer | 3D view | Full page | Quick page |
| --- | --- | --- | --- | --- | --- |
| W | W/L (window/level: brightness and contrast) tool | yes | yes | yes | yes |
| M | Pan tool | yes | yes | yes | yes |
| Z | Zoom tool | yes | yes | yes | yes |
| B | Scroll through a series | yes | - | - | - |
| L | Length | yes | yes | yes | yes |
| A | Angle | yes | - | yes | - |
| C | Cobb angle | yes | - | - | see note |
| E | Ellipse | yes | - | - | - |
| G | Closed polygon | yes | - | - | - |
| O | Open polygon | yes | - | - | - |
| T | Annotation | yes | roll tool | - | - |
| 0 | Default W/L (1 in the viewer: full dynamic) | yes | yes | yes | yes |
| F | Full screen | yes | yes | yes | yes |
| F11 | Invert | yes | yes | yes | yes |
| F12 | Annotations (reference lines on Full) | yes | yes | yes | - |
| Ctrl+0 | Fit | yes | yes | yes | yes |
| Ctrl+1 | Actual size | yes | - | yes | yes |
| Ctrl and + or - | Zoom | yes | yes | yes | yes |
| Ctrl+arrows | Move the image | yes | yes | - | yes |
| Ctrl+[ and Ctrl+] | Rotate 90 degrees | yes | - | - | - |
| Ctrl+Shift+[ and ] | Flip horizontal, flip vertical | yes | - | - | - |
| Ctrl+Shift+\ | Reset the view | yes | yes | yes | yes |
| Esc | Deselect, or stop what is armed | yes | yes | yes | yes |
| Delete | Delete what is selected | yes | cuts the scalpel area | yes | yes |
| Ctrl+Z | Undo | - | yes | yes | yes |
| Ctrl+E | Export the view | yes | yes | - | - |
| Ctrl+P | Print | yes | - | - | - |
| Up and Down | Previous and next image | yes | turn | size | size |
| Page Up and Page Down | 10 images back and forward | yes | - | size | size |
| Left and Right | Previous and next series | yes | turn | - | - |
| Home and End | First and last series | yes | - | - | - |
| Space | Cine | yes | - | - | - |
| Tab | Moves the focus; next implant on Quick (RadiAnt's next panel is not built) | - | - | focus | next implant |

Note: on the Quick page C is Copy to the other side, because it has no Cobb tool.

Templating only, the same on both pages: Up and Down or Page Up and Page Down size, Shift+arrows move 0.5 mm (with Alt too,
2 mm), comma and full stop turn 0.5 degrees (with Shift 2 degrees), N next implant. Quick page also: I add an implant,
Shift+R and Shift+L side, C copy, V valgus, S sizes, Alt+1, 2, 3 knee views, ? this list.

Rotating or flipping the film has no key on the templating pages on purpose: those pages hold the plan's side and orientation.

Moved away from Weasis's defaults: study Alt+Left and Right, patient Alt+Up and Down, first and last image Shift+Home and
End, reset view from Esc to Ctrl+Shift+\, docking externalize Ctrl+Shift+X. A key the user has changed in Preferences stays as
the user set it.

The same word everywhere: Length (was Line, Ruler, Distance), W/L, Invert, Scroll, Rotate, Cobb angle, Open polygon, Closed
polygon, Annotation, Tags (DICOM tags), Capture, Magnifier. "Dimension" appears only in catalogue wording, not as a tool.

CT window keys, as RadiAnt where the viewer has that preset: 2 Abdomen, 4 Bone, 5 Brain, 6 Mediastinum, 7 Lung, 8 MIP. A film with a
second DICOM window uses 2 for that window.

Kept from Weasis, no RadiAnt key clashes: R rotate tool, H crosshair, K key object, Q context menu, I annotations too.

On a Mac: F11 and F12 need Fn, and F11 may be taken by Show Desktop until it is changed in System Settings. Ctrl+arrows belong to
macOS, so use Cmd+arrows.

In the viewer Delete and Esc work once a Measure or Draw tool has been chosen on a mouse button.
