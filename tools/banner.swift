// Renders the 320x180 leanback banner: swift tools/banner.swift app/src/main/res/drawable-nodpi/banner.png
import AppKit

let size = NSSize(width: 320, height: 180)
let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: 320, pixelsHigh: 180, bitsPerSample: 8,
                           samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB,
                           bytesPerRow: 0, bitsPerPixel: 0)!
NSGraphicsContext.saveGraphicsState()
NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
NSColor(calibratedRed: 0.80, green: 0.10, blue: 0.13, alpha: 1).setFill()
NSRect(origin: .zero, size: size).fill()
let attrs: [NSAttributedString.Key: Any] = [
    .font: NSFont.boldSystemFont(ofSize: 60),
    .foregroundColor: NSColor.white,
]
let text = NSAttributedString(string: "红果短剧", attributes: attrs)
let t = text.size()
text.draw(at: NSPoint(x: (size.width - t.width) / 2, y: (size.height - t.height) / 2))
NSGraphicsContext.restoreGraphicsState()
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: CommandLine.arguments[1]))
