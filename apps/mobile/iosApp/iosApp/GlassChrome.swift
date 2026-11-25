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
                // Prototype: scrim at 28 %.
                Color.black.opacity(0.28)
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
        .animation(Self.tabMotion, value: snap)
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
        Tab(label: "Codes", symbol: "clock.badge.checkmark", selectedSymbol: "clock.badge.checkmark.fill"),
        Tab(label: "Generator", symbol: "key.horizontal", selectedSymbol: "key.horizontal.fill"),
        Tab(label: "Einstellungen", symbol: "gearshape", selectedSymbol: "gearshape.fill"),
    ]

    private func tabsLayer(_ snap: ChromeSnapshot) -> some View {
        let selected = Int(snap.tab)
        let compact = snap.compact && !menuOpen
        let items = snap.itemTypes
        return VStack(alignment: .trailing, spacing: 12) {
            // The prototype's FAB menu sits above the tab bar at the right edge.
            if selected == 0 {
                VStack(alignment: .trailing, spacing: 8) {
                    if menuMounted {
                        VStack(alignment: .trailing, spacing: 4) {
                            ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                                menuItem(item, index: index, count: items.count)
                            }
                        }
                    }
                    fab
                }
                .padding(.trailing, 0)
            }
            // Prototype: the bar spans the width, tabs evenly spread; scrolling shrinks it to the
            // selected tab.
            // The selected tab takes the free width and its pill fills exactly that slot; the
            // others keep a fixed 64 pt. Nothing is wider than its slot, so the pill never runs
            // past the bar, and one smooth animation (no overshoot) moves pill, label and icons.
            HStack(spacing: 4) {
                ForEach(tabs.indices, id: \.self) { i in
                    if !compact || i == selected {
                        tabButton(i, selected: i == selected, showLabel: !compact)
                            .frame(width: compact || i == selected ? nil : 64)
                            .frame(maxWidth: !compact && i == selected ? .infinity : nil)
                    }
                }
            }
            .padding(6)
            .animation(Self.tabMotion, value: selected)
            .animation(Self.tabMotion, value: compact)
            .frame(maxWidth: compact ? nil : .infinity)
            .glassEffect(.regular.interactive(), in: .capsule)
            .glassEffectID("bar", in: glass)
            .frame(maxWidth: .infinity, alignment: compact ? .leading : .center)
        }
    }

    /// One entry of the FAB menu, exactly as the prototype's M3 Expressive FabMenu: a 56 pt
    /// primary-container pill with elevation; it grows from 60 % at its bottom-right corner, rises
    /// 24 pt and fades in, 30 ms after the one below it (closing: top first).
    private func menuItem(_ item: ChromeAction, index: Int, count: Int) -> some View {
        let delay = Double(itemsOut ? count - 1 - index : index) * 0.03
        return Button {
            closeMenu()
            NativeChrome.shared.createItem(id: item.id)
        } label: {
            HStack(spacing: 8) {
                Image(systemName: item.symbol).font(.system(size: 20, weight: .medium)).frame(width: 24)
                Text(item.label).font(.system(size: 16, weight: .medium)).tracking(0.15)
            }
            .padding(.leading, 16)
            .padding(.trailing, 24)
            .frame(height: 56)
            .background(M3.primaryContainer, in: Capsule())
            .shadow(color: .black.opacity(0.18), radius: 6, y: 3)
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(M3.onPrimaryContainer)
        .scaleEffect(itemsOut ? 1 : 0.6, anchor: .bottomTrailing)
        .offset(y: itemsOut ? 0 : 24)
        .animation(Self.spatialFast.delay(delay), value: itemsOut)
        .opacity(itemsOut ? 1 : 0)
        .animation(Self.effectsFast.delay(delay), value: itemsOut)
        .allowsHitTesting(itemsOut)
    }

    private func tabButton(_ i: Int, selected: Bool, showLabel: Bool) -> some View {
        let tab = tabs[i]
        return Button {
            NativeChrome.shared.selectTab(index: Int32(i))
        } label: {
            HStack(spacing: 8) {
                Image(systemName: selected ? tab.selectedSymbol : tab.symbol)
                    .font(.system(size: 19, weight: .semibold))
                    .frame(width: 24, height: 24)
                    .contentTransition(.symbolEffect(.replace.downUp))
                if selected && showLabel {
                    Text(tab.label)
                        .font(.system(size: 15, weight: .bold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.75)
                        .transition(.opacity.combined(with: .scale(scale: 0.9, anchor: .leading)))
                }
            }
            .padding(.horizontal, 14)
            .frame(maxWidth: selected && showLabel ? .infinity : nil, minHeight: 48)
            .frame(minWidth: 48)
            .foregroundStyle(selected ? M3.onSecondaryContainer : M3.onSurface)
            .background {
                if selected {
                    Capsule().fill(M3.secondaryContainer).matchedGeometryEffect(id: "pill", in: glass)
                }
            }
            .clipShape(Capsule())
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(tab.label)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// Tab bar: one calm, even motion for pill, label and icons (no overshoot past the bar).
    private static let tabMotion = Animation.smooth(duration: 0.32)

    /// M3 Expressive "fast spatial": 394 ms with about 9 % overshoot (the prototype's curve).
    private static let spatialFast = Animation.spring(duration: 0.394, bounce: 0.28)
    /// M3 "fast effects": 173 ms ease-out, for colour and opacity.
    private static let effectsFast = Animation.easeOut(duration: 0.173)

    private func openMenu() {
        menuMounted = true
        withAnimation(Self.spatialFast) { menuOpen = true }
        // One frame later, so the items start at the button and fly out.
        DispatchQueue.main.async { itemsOut = true }
    }

    private func closeMenu(animated: Bool = true) {
        guard menuMounted || menuOpen else { return }
        withAnimation(animated ? Self.spatialFast : nil) { menuOpen = false }
        itemsOut = false
        guard animated else { menuMounted = false; return }
        let last = Double(max(0, (model.snap?.itemTypes.count ?? 1) - 1)) * 0.03
        DispatchQueue.main.asyncAfter(deadline: .now() + last + 0.4) {
            if !menuOpen { menuMounted = false }
        }
    }

    /// The prototype's FAB: a 56 pt primary-container square (16 pt corners) that turns into a
    /// primary circle while the menu is open; the plus turns a quarter and becomes a close icon.
    private var fab: some View {
        Button {
            menuOpen ? closeMenu() : openMenu()
        } label: {
            Image(systemName: menuOpen ? "xmark" : "plus")
                .font(.system(size: 22, weight: .semibold))
                .contentTransition(.symbolEffect(.replace))
                .rotationEffect(.degrees(menuOpen ? 90 : 0))
                .frame(width: 56, height: 56)
                .foregroundStyle(menuOpen ? M3.onPrimary : M3.onPrimaryContainer)
                .background(menuOpen ? M3.primary : M3.primaryContainer, in: RoundedRectangle(cornerRadius: menuOpen ? 28 : 16, style: .continuous))
                .shadow(color: .black.opacity(0.18), radius: 6, y: 3)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .animation(Self.spatialFast, value: menuOpen)
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
