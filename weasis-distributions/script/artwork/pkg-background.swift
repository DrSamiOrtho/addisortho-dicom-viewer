import AppKit
// usage: swift pkgbg.swift wordmark.svg out.png
// A transparent canvas of 180 x 418 points at 2x (360 x 836 px, written as 144 dpi): a black
// rounded tile 164 points wide in the installer's left sidebar, 24 points from the bottom, with
// the lime wordmark inside it. Nothing reaches the content pane, which starts near 181 points.
let args = CommandLine.arguments
guard let logo = NSImage(contentsOfFile: args[1]) else { fatalError("cannot read svg") }
let pw = 180.0, ph = 418.0, scale = 2.0
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: Int(pw * scale), pixelsHigh: Int(ph * scale),
  bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
  colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
rep.size = NSSize(width: pw, height: ph)
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSGraphicsContext.current?.imageInterpolation = .high
let tileW = 164.0, pad = 10.0
let logoW = tileW - 2 * pad, logoH = logoW * logo.size.height / logo.size.width
let tile = NSRect(x: (pw - tileW) / 2, y: 24, width: tileW, height: logoH + 2 * pad)
NSColor.black.setFill()
NSBezierPath(roundedRect: tile, xRadius: 12, yRadius: 12).fill()
logo.draw(in: NSRect(x: tile.minX + pad, y: tile.minY + pad, width: logoW, height: logoH))
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: args[2]))
