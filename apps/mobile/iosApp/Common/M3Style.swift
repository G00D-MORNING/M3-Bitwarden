import SwiftUI

/// Material 3 Expressive look for the SwiftUI screens (setup, lock, AutoFill), matching the
/// shared Compose theme and the design prototype: tonal surfaces, pill buttons, big shapes.
enum M3 {
    static func color(light: UInt32, dark: UInt32) -> Color {
        Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
    }

    static let primary = color(light: 0x175DDC, dark: 0xB2C5FF)
    static let onPrimary = color(light: 0xFFFFFF, dark: 0x002B75)
    static let primaryContainer = color(light: 0xDAE2FF, dark: 0x0A3A9A)
    static let onPrimaryContainer = color(light: 0x0A3A9A, dark: 0xDAE2FF)
    static let secondaryContainer = color(light: 0xDAE2F9, dark: 0x3F4759)
    static let onSecondaryContainer = color(light: 0x3F4759, dark: 0xDAE2F9)
    static let tertiary = color(light: 0x006A60, dark: 0x82D5C7)
    static let surface = color(light: 0xFAF8FF, dark: 0x121318)
    static let surfaceContainer = color(light: 0xEEEDF4, dark: 0x1E1F25)
    static let surfaceContainerHigh = color(light: 0xE8E7EF, dark: 0x282A2F)
    static let surfaceContainerHighest = color(light: 0xE2E2E9, dark: 0x33343A)
    static let onSurface = color(light: 0x1A1B21, dark: 0xE2E2E9)
    static let onSurfaceVariant = color(light: 0x44474F, dark: 0xC5C6D0)
    static let error = color(light: 0xBA1A1A, dark: 0xFFB4AB)
    static let errorContainer = color(light: 0xFFDAD6, dark: 0x93000A)
    static let onErrorContainer = color(light: 0x93000A, dark: 0xFFDAD6)
}

extension UIColor {
    convenience init(hex: UInt32) {
        self.init(red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255, blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
    }
}

/// Twelve-sided "cookie" from the Material shape library, slowly rotating like in the prototype.
struct CookieShape: Shape {
    var lobes = 12
    var depth: CGFloat = 0.06

    func path(in rect: CGRect) -> Path {
        let c = CGPoint(x: rect.midX, y: rect.midY)
        let r = min(rect.width, rect.height) / 2
        var p = Path()
        let steps = 360
        for i in 0...steps {
            let a = CGFloat(i) / CGFloat(steps) * 2 * .pi
            let rr = r * (1 - depth + depth * cos(CGFloat(lobes) * a))
            let pt = CGPoint(x: c.x + rr * cos(a), y: c.y + rr * sin(a))
            if i == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
        }
        p.closeSubpath()
        return p
    }
}

/// Emblem: rotating cookie with an SF Symbol in the middle.
struct M3Emblem: View {
    let symbol: String
    var size: CGFloat = 176
    var busy = false
    @State private var angle = 0.0

    var body: some View {
        ZStack {
            CookieShape()
                .fill(M3.primaryContainer)
                .rotationEffect(.degrees(angle))
            Image(systemName: symbol)
                .font(.system(size: size * 0.32, weight: .semibold))
                .foregroundStyle(M3.onPrimaryContainer)
        }
        .frame(width: size, height: size)
        .onAppear { withAnimation(.linear(duration: busy ? 1.4 : 30).repeatForever(autoreverses: false)) { angle = 360 } }
    }
}

/// Filled M3 text field: tonal box that rounds further and gets a primary outline on focus.
struct M3Field: View {
    let label: String
    @Binding var text: String
    var symbol: String?
    var secure = false
    var content: UITextContentType?
    var keyboard: UIKeyboardType = .default
    var onSubmit: () -> Void = {}
    @FocusState private var focused: Bool
    @State private var revealed = false

    var body: some View {
        HStack(spacing: 12) {
            if let symbol { Image(systemName: symbol).foregroundStyle(M3.onSurfaceVariant) }
            VStack(alignment: .leading, spacing: 2) {
                if focused || !text.isEmpty {
                    Text(label).font(.caption).foregroundStyle(focused ? M3.primary : M3.onSurfaceVariant)
                }
                Group {
                    if secure && !revealed {
                        SecureField(focused ? "" : label, text: $text)
                    } else {
                        TextField(focused ? "" : label, text: $text)
                    }
                }
                .textContentType(content)
                .keyboardType(keyboard)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focused)
                .onSubmit(onSubmit)
            }
            if secure {
                Button { revealed.toggle() } label: {
                    Image(systemName: revealed ? "eye.slash" : "eye").foregroundStyle(M3.onSurfaceVariant)
                }
                .accessibilityLabel(revealed ? "Verbergen" : "Anzeigen")
            }
        }
        .padding(.horizontal, 16)
        .frame(minHeight: 56)
        .background(focused ? M3.surfaceContainerHigh : M3.surfaceContainerHighest, in: RoundedRectangle(cornerRadius: focused ? 24 : 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: focused ? 24 : 16, style: .continuous).strokeBorder(focused ? M3.primary : .clear, lineWidth: 2))
        .animation(.spring(response: 0.35, dampingFraction: 0.7), value: focused)
    }
}

/// Pill buttons in the M3 Expressive sizes; the corners tighten while pressed.
struct M3ButtonStyle: ButtonStyle {
    enum Kind { case filled, tonal, text }
    var kind: Kind = .filled
    var height: CGFloat = 56

    func makeBody(configuration: Configuration) -> some View {
        let (bg, fg): (Color, Color) = switch kind {
        case .filled: (M3.primary, M3.onPrimary)
        case .tonal: (M3.secondaryContainer, M3.onSecondaryContainer)
        case .text: (.clear, M3.primary)
        }
        return configuration.label
            .font(.system(size: height >= 56 ? 17 : 15, weight: .semibold))
            .frame(maxWidth: kind == .text ? nil : .infinity, minHeight: height)
            .padding(.horizontal, 20)
            .foregroundStyle(fg)
            .background(bg, in: RoundedRectangle(cornerRadius: configuration.isPressed ? 12 : height / 2, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.spring(response: 0.3, dampingFraction: 0.6), value: configuration.isPressed)
    }
}

/// Error banner in error-container colors.
struct M3Error: View {
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            Image(systemName: "exclamationmark.circle.fill")
            Text(text).font(.callout)
            Spacer(minLength: 0)
        }
        .padding(16)
        .foregroundStyle(M3.onErrorContainer)
        .background(M3.errorContainer, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
    }
}
