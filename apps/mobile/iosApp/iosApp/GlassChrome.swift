import Shared
import SwiftUI

/// What the Compose UI wants on its floating bars right now (see `NativeChrome` in Kotlin).
@MainActor
final class ChromeModel: ObservableObject {
    @Published private(set) var snap: ChromeSnapshot?

    init() {
        NativeChrome.shared.onChange = { [weak self] snap in self?.snap = snap }
    }
}

/// The Compose vault with the Liquid Glass bars laid over it (iOS 26 and later).
struct VaultHost: View {
    @StateObject private var chrome = ChromeModel()

    var body: some View {
        ZStack {
            ComposeView().ignoresSafeArea()
            if #available(iOS 26, *) {
                GlassChrome(model: chrome)
            }
        }
    }
}

/// Floating controls in Liquid Glass with Material 3 Expressive behaviour:
/// - tab bar: the selected tab grows into a pill with its label; scrolling down shrinks the bar
///   to the selected tab,
/// - new-item button: a rounded square that turns round while the item types fly out of its
///   corner one after another (as the prototype's M3 Expressive FAB menu: each grows from 60 %,
///   rises 24 pt and fades in, 30 ms apart, nearest first; closing runs the other way),
/// - item toolbar: copy/open actions plus the edit button. Bar and button morph into each other
///   when an item opens, because they share their glass identities.
@available(iOS 26, *)
struct GlassChrome: View {
    @ObservedObject var model: ChromeModel
    @Namespace private var glass
    @State private var menuOpen = false
    /// The menu items are on screen (also while they fly back in).
    @State private var menuMounted = false
    /// The items are out; set one frame after mounting so they animate in from the button.
    @State private var itemsOut = false
    @State private var actionTaps = 0

    private let spring = Animation.spring(response: 0.42, dampingFraction: 0.78)

    var body: some View {
        let snap = model.snap
        let visible = snap.map { !$0.modal } ?? false
        ZStack(alignment: .bottom) {
            if menuOpen {
                Color.black.opacity(0.18)
                    .ignoresSafeArea()
                    .onTapGesture { closeMenu() }
                    .allowsHitTesting(menuOpen)
                    .transition(.opacity)
            }
            if let snap, visible {
                GlassEffectContainer(spacing: 8) {
                    if snap.onTabs {
                        tabsLayer(snap)
                    } else if snap.primary != nil || !snap.actions.isEmpty {
                        toolbar(snap)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 6)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
        .ignoresSafeArea(.keyboard)
        .animation(spring, value: snap)
        .animation(spring, value: menuOpen)
        .onChange(of: snap?.onTabs) { _, onTabs in if onTabs != true { closeMenu(animated: false) } }
        .onChange(of: snap?.tab) { _, _ in closeMenu(animated: false) }
        .sensoryFeedback(.selection, trigger: snap?.tab)
        .sensoryFeedback(.impact(weight: .light), trigger: menuOpen)
        .sensoryFeedback(.success, trigger: actionTaps)
    }

    // MARK: Tabs

    private struct Tab {
        let label: String
        let symbol: String
        let selectedSymbol: String
    }

    private let tabs = [
        Tab(label: "Tresor", symbol: "lock.shield", selectedSymbol: "lock.shield.fill"),
        Tab(label: "Generator", symbol: "wand.and.sparkles", selectedSymbol: "wand.and.sparkles"),
        Tab(label: "Einstellungen", symbol: "gearshape", selectedSymbol: "gearshape.fill"),
    ]

    private func tabsLayer(_ snap: ChromeSnapshot) -> some View {
        let selected = Int(snap.tab)
        let compact = snap.compact && !menuOpen
        let items = snap.itemTypes
        return VStack(alignment: .trailing, spacing: 12) {
            if menuMounted {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    // Nearest to the button first when opening, top first when closing.
                    let delay = Double(itemsOut ? items.count - 1 - index : index) * 0.03
                    Button {
                        closeMenu()
                        NativeChrome.shared.createItem(id: item.id)
                    } label: {
                        Label(item.label, systemImage: item.symbol)
                            .font(.system(size: 17, weight: .semibold))
                            .padding(.horizontal, 22)
                            .frame(height: 54)
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(M3.onPrimaryContainer)
                    .glassEffect(.regular.tint(M3.primaryContainer.opacity(0.75)).interactive(), in: .capsule)
                    .scaleEffect(itemsOut ? 1 : 0.6, anchor: .bottomTrailing)
                    .offset(y: itemsOut ? 0 : 24)
                    .opacity(itemsOut ? 1 : 0)
                    .animation(Self.itemSpring.delay(delay), value: itemsOut)
                    .allowsHitTesting(itemsOut)
                }
            }
            HStack(alignment: .bottom, spacing: 12) {
                HStack(spacing: 2) {
                    ForEach(tabs.indices, id: \.self) { i in
                        if !compact || i == selected {
                            tabButton(i, selected: i == selected, showLabel: !compact)
                        }
                    }
                }
                .padding(6)
                .glassEffect(.regular.interactive(), in: .capsule)
                .glassEffectID("bar", in: glass)

                if selected == 0 {
                    fab
                }
            }
        }
    }

    private func tabButton(_ i: Int, selected: Bool, showLabel: Bool) -> some View {
        let tab = tabs[i]
        return Button {
            NativeChrome.shared.selectTab(index: Int32(i))
        } label: {
            HStack(spacing: 8) {
                Image(systemName: selected ? tab.selectedSymbol : tab.symbol)
                    .font(.system(size: 19, weight: .semibold))
                if selected && showLabel {
                    Text(tab.label).font(.system(size: 15, weight: .bold)).lineLimit(1).fixedSize()
                }
            }
            .padding(.horizontal, selected && showLabel ? 18 : 14)
            .frame(minWidth: 48, minHeight: 48)
            .foregroundStyle(selected ? M3.onSecondaryContainer : M3.onSurface)
            .background {
                if selected {
                    Capsule().fill(M3.secondaryContainer).matchedGeometryEffect(id: "pill", in: glass)
                }
            }
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// M3 Expressive "fast spatial" spring: quick, with a little overshoot.
    private static let itemSpring = Animation.spring(response: 0.36, dampingFraction: 0.68)

    private func openMenu() {
        menuMounted = true
        withAnimation(spring) { menuOpen = true }
        // One frame later, so the items start at the button and fly out.
        DispatchQueue.main.async { itemsOut = true }
    }

    private func closeMenu(animated: Bool = true) {
        guard menuMounted || menuOpen else { return }
        withAnimation(animated ? spring : nil) { menuOpen = false }
        itemsOut = false
        guard animated else { menuMounted = false; return }
        let last = Double(max(0, (model.snap?.itemTypes.count ?? 1) - 1)) * 0.03
        DispatchQueue.main.asyncAfter(deadline: .now() + last + 0.3) {
            if !menuOpen { menuMounted = false }
        }
    }

    private var fab: some View {
        Button {
            menuOpen ? closeMenu() : openMenu()
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 24, weight: .semibold))
                .rotationEffect(.degrees(menuOpen ? 135 : 0))
                .frame(width: 60, height: 60)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(M3.onPrimary)
        // M3 Expressive FAB menu: the rounded square turns into a circle while open.
        .glassEffect(.regular.tint(M3.primary).interactive(), in: RoundedRectangle(cornerRadius: menuOpen ? 30 : 18, style: .continuous))
        .glassEffectID("fab", in: glass)
        .accessibilityLabel(menuOpen ? "Menü schließen" : "Neuer Eintrag")
    }

    // MARK: Item toolbar

    private func toolbar(_ snap: ChromeSnapshot) -> some View {
        HStack(alignment: .center, spacing: 12) {
            if !snap.actions.isEmpty {
                HStack(spacing: 4) {
                    ForEach(snap.actions, id: \.id) { action in
                        Button {
                            actionTaps += 1
                            NativeChrome.shared.runAction(id: action.id)
                        } label: {
                            Image(systemName: action.symbol)
                                .font(.system(size: 19, weight: .semibold))
                                .frame(width: 50, height: 50)
                                .contentShape(Circle())
                        }
                        .buttonStyle(.plain)
                        .foregroundStyle(M3.onSurface)
                        .disabled(!action.enabled)
                        .opacity(action.enabled ? 1 : 0.35)
                        .accessibilityLabel(action.label)
                    }
                }
                .padding(6)
                .glassEffect(.regular.interactive(), in: .capsule)
                .glassEffectID("bar", in: glass)
            }
            if let primary = snap.primary {
                Button {
                    NativeChrome.shared.runAction(id: primary.id)
                } label: {
                    Image(systemName: primary.symbol)
                        .font(.system(size: 22, weight: .semibold))
                        .frame(width: 60, height: 60)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .foregroundStyle(M3.onPrimary)
                .glassEffect(.regular.tint(M3.primary).interactive(), in: RoundedRectangle(cornerRadius: 18, style: .continuous))
                .glassEffectID("fab", in: glass)
                .accessibilityLabel(primary.label)
            }
        }
    }
}
