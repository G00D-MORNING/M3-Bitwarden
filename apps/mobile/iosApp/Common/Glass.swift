import SwiftUI

/*
 * Liquid Glass for the floating layer (bars, buttons, controls) on iOS 26 and later, while the
 * content keeps the Material 3 Expressive look: tonal surfaces, big shapes, pill rows.
 * Older systems get the M3 tonal fill in the same shape.
 */

extension View {
    /// Glass in [shape]; [tint] colors it (filled buttons), nil keeps it clear (bars, tonal buttons).
    @ViewBuilder
    func m3Glass(tint: Color? = nil, interactive: Bool = true, in shape: some Shape = Capsule(), fallback: Color = M3.surfaceContainerHighest) -> some View {
        if #available(iOS 26, *) {
            glassEffect(Glass.regular.tint(tint).interactive(interactive), in: shape)
        } else {
            background(tint ?? fallback, in: shape)
        }
    }
}

/// Round glass close button (iOS 26 sheets), a text button before that.
struct GlassCloseButton: View {
    var label = "Abbrechen"
    let action: () -> Void

    var body: some View {
        if #available(iOS 26, *) {
            Button(action: action) {
                Image(systemName: "xmark")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(M3.onSurface)
            .glassEffect(.regular.interactive(), in: .circle)
            .accessibilityLabel(label)
        } else {
            Button(label, action: action).buttonStyle(M3ButtonStyle(kind: .text, height: 40))
        }
    }
}

/// Soft Material shapes behind the setup pages, so the glass controls have something to refract.
struct M3Backdrop: View {
    @State private var turn = 0.0

    var body: some View {
        GeometryReader { geo in
            ZStack {
                M3.surface
                CookieShape(lobes: 9, depth: 0.08)
                    .fill(M3.primaryContainer.opacity(0.55))
                    .frame(width: geo.size.width * 0.9, height: geo.size.width * 0.9)
                    .rotationEffect(.degrees(turn))
                    .offset(x: geo.size.width * 0.45, y: -geo.size.height * 0.32)
                CookieShape(lobes: 4, depth: 0.16)
                    .fill(M3.secondaryContainer.opacity(0.6))
                    .frame(width: geo.size.width * 0.75, height: geo.size.width * 0.75)
                    .rotationEffect(.degrees(-turn * 0.7))
                    .offset(x: -geo.size.width * 0.42, y: geo.size.height * 0.36)
            }
        }
        .ignoresSafeArea()
        .onAppear { withAnimation(.linear(duration: 90).repeatForever(autoreverses: false)) { turn = 360 } }
        .accessibilityHidden(true)
    }
}
