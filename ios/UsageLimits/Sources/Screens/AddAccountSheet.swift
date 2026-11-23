#if DEBUG
import Combine
#endif
import SwiftUI
import UIKit
import UsageLimitsKit

/// Signing in, from a phone.
///
/// Device-code providers show a short code that the user approves in a browser anywhere, while
/// loopback providers complete through the local listener. The interesting device state is not
/// "loading" and "done" but "here is your code, go and use it, I am still waiting" — so the code
/// stays on screen the whole time rather than being replaced by a spinner.
///
/// Every listed provider now has a phone-compatible sign-in flow, and the screen describes the
/// selected flow before it starts so the browser step is predictable.
struct AddAccountSheet: View {
    @Environment(\.usageAppearance) private var appearance
    private var colors: UsagePalette { appearance.colors }

    @EnvironmentObject private var store: UsageStore
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    /// Where the sheet is in the flow. One value rather than several booleans, so "waiting" and
    /// "failed" cannot both be true and leave the screen showing a contradiction.
    enum Stage: Equatable {
        case choosing
        case starting(ProviderID)
        case waiting(ProviderID, DeviceLoginChallenge)
        /// No flow to drive: the user has to fetch a key and paste it.
        case awaitingKey(ProviderID)
        /// Kimi and Z.ai run a China and an international service; keys do not cross.
        case choosingRegion(ProviderID)
        case failed(ProviderID, String)
    }

    @ReducesMotion private var reduceMotion
    @State private var localStage: Stage = .choosing
    @State private var displayedStage: Stage = .choosing
    @State private var stepOpacity = 1.0
    @State private var stepRevision = 0

    private var stage: Stage {
        get {
            #if DEBUG
            if let drivenStage { return drivenStage.wrappedValue }
            #endif
            return localStage
        }
        nonmutating set {
            #if DEBUG
            if let drivenStage { drivenStage.wrappedValue = newValue; return }
            #endif
            localStage = newValue
        }
    }
    #if DEBUG
    private var drivenStage: Binding<Stage>?
    private var captureStep: ((Double, Bool) -> Void)?
    private var holdExit = false
    private var retainChooserAction: ((@escaping @MainActor () -> Void) -> Void)?
    #endif
    @State private var login: Task<Void, Never>?
    @State private var loopback = LoopbackSignIn()
    @State private var copied = false
    @State private var pastedKey = ""
    /// The region picked for a regional provider, or nil until the user picks.
    @State private var region: ProviderRegion?

    private let initialProvider: ProviderID?

    init(initialProvider: ProviderID? = nil) {
        self.initialProvider = initialProvider
    }

    #if DEBUG
    static func rendering(stage: Binding<Stage>, reduceMotion: Bool,
                          captureStep: @escaping (Double, Bool) -> Void) -> some View {
        // Apply the app's environment key, not the rendering target's copy of UsageMotion.
        AddAccountSheet(stage: stage, captureStep: captureStep)
            .environment(\.reduceMotionOverride, reduceMotion)
    }

    private init(stage: Binding<Stage>, captureStep: ((Double, Bool) -> Void)? = nil,
                 holdExit: Bool = false, retainChooserAction: ((@escaping @MainActor () -> Void) -> Void)? = nil) {
        initialProvider = nil
        drivenStage = stage
        self.captureStep = captureStep
        self.holdExit = holdExit
        self.retainChooserAction = retainChooserAction
        _displayedStage = State(initialValue: stage.wrappedValue)
    }

    static func motionAccessibilityFixture() -> some View {
        SignInMotionAccessibilityFixture()
    }
    #endif

    private struct StepTarget: Equatable {
        let stage: Stage
        let reduceMotion: Bool
        #if DEBUG
        let holdExit: Bool
        #endif
    }

    private var acceptsInput: Bool { reduceMotion || (displayedStage == stage && stepOpacity == 1) }

    private var capturesStepFrames: Bool {
        #if DEBUG
        captureStep != nil
        #else
        false
        #endif
    }

    var body: some View {
        #if DEBUG
        let target = StepTarget(stage: stage, reduceMotion: reduceMotion, holdExit: holdExit)
        #else
        let target = StepTarget(stage: stage, reduceMotion: reduceMotion)
        #endif
        // A cut must paint the requested card in this update, not in a later onChange update.
        let visibleStage = target.reduceMotion ? target.stage : displayedStage
        let opacity = target.reduceMotion ? 1 : stepOpacity
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    switch visibleStage {
                    case .choosing:
                        chooser
                    case .starting(let provider):
                        starting(provider)
                    case .waiting(let provider, let challenge):
                        waiting(provider, challenge)
                    case .awaitingKey(let provider):
                        awaitingKey(provider)
                    case .choosingRegion(let provider):
                        choosingRegion(provider)
                    case .failed(let provider, let message):
                        failure(provider, message)
                    }
                }
                .id(stageIdentity(visibleStage))
                .disabled(!acceptsInput)
                .allowsHitTesting(acceptsInput)
                .accessibilityHidden(!acceptsInput)
                .modifier(SignInStepOpacity(value: opacity, capturesFrames: capturesStepFrames) { [revision = stepRevision] value in
                    guard revision == stepRevision else { return }
                    #if DEBUG
                    captureStep?(value, !target.reduceMotion && displayedStage != stage)
                    #endif
                    if value == 0, stepOpacity == 0 { finishExit() }
                })
                // Switching the preference must also discard an in-flight interpolation to 1.
                .id(target.reduceMotion)
                .transaction { if target.reduceMotion { $0.animation = nil; $0.disablesAnimations = true } }
                .padding(16)
                .readableWidth()
            }
            .accessibilityIdentifier("provider-picker-scroll")
            .background(colors.ground)
            .navigationTitle("Add account")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        // The poll is a long-running task the user has just abandoned. Left
                        // running it would keep talking to the provider about a sign-in nobody
                        // is going to approve.
                        login?.cancel()
                        dismiss()
                    }
                }
            }
        }
        .onAppear {
            if let initialProvider, stage == .choosing { select(initialProvider) }
        }
        .onChange(of: target) { retargetStep($0) }
        .onDisappear { login?.cancel() }
    }

    private func retargetStep(_ target: StepTarget) {
        guard !target.reduceMotion else {
            stepRevision += 1
            var transaction = Transaction(animation: nil)
            transaction.disablesAnimations = true
            withTransaction(transaction) {
                displayedStage = target.stage
                stepOpacity = 1
            }
            return
        }
        #if DEBUG
        if target.holdExit { return }
        #endif
        let returning = displayedStage == target.stage
        guard returning || stepOpacity != 0 else { return }
        stepRevision += 1
        withAnimation(returning ? Motion.enterFromHidden(reduceMotion: false) : Motion.exit.animation(reduceMotion: false)) {
            stepOpacity = returning ? 1 : 0
        }
    }

    private func finishExit() {
        guard displayedStage != stage else { return }
        var transaction = Transaction(animation: nil)
        transaction.disablesAnimations = true
        withTransaction(transaction) { displayedStage = stage }
        withAnimation(Motion.enterFromHidden(reduceMotion: reduceMotion)) { stepOpacity = 1 }
    }

    private func stageIdentity(_ stage: Stage) -> Int {
        switch stage {
        case .choosing: return 0
        case .starting: return 1
        case .waiting: return 2
        case .awaitingKey: return 3
        case .failed: return 4
        case .choosingRegion: return 5
        }
    }

    // MARK: - Choosing

    /// The providers as ledger rows in one card, 56pt apart.
    private var chooser: some View {
        UsageCard(padding: 0, spacing: 0) {
            ForEach(Array(ProviderID.allCases.enumerated()), id: \.offset) { index, provider in
                if index > 0 { LedgerDivider() }
                let action: @MainActor () -> Void = { if stage == .choosing, acceptsInput { select(provider) } }
                Button { action() } label: {
                    HStack(spacing: 12) {
                        ProviderBadge(provider: provider)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(provider.displayName)
                                .usageFont(.section)
                                .foregroundStyle(colors.ink)
                            // Named before the tap, not after: a user should know what the button is
                            // about to do with their account before it does it. The two flows feel
                            // different enough that saying which one is coming is worth a line.
                            Text(signInHint(for: provider))
                                .usageFont(.body)
                                .foregroundStyle(colors.muted)
                        }
                        Spacer()
                        Image(systemName: "chevron.right")
                            .foregroundStyle(colors.faint)
                            .accessibilityHidden(true)
                    }
                    .padding(.horizontal, 16)
                    .frame(minHeight: 56)
                    .contentShape(Rectangle())
                }
                .buttonStyle(ProviderPickerRowStyle())
                #if DEBUG
                .onAppear { if provider == .kimi { retainChooserAction?(action) } }
                #endif
            }
        }
    }

    private func choosingRegion(_ provider: ProviderID) -> some View {
        UsageCard {
            Text("\(provider.displayName) region")
                .usageFont(.section)
                .foregroundStyle(colors.ink)
            Text("Keys and sign-ins only work on the service they were issued by. Pick the one your account is on.")
                .usageFont(.body)
                .foregroundStyle(colors.muted)
            ForEach(ProviderRegion.options(for: provider), id: \.self) { option in
                Button(DisplayText.regionLabel(option, for: provider)) {
                    region = option
                    start(provider)
                }
                .buttonStyle(OutlineButtonStyle(fullWidth: true))
            }
        }
    }

    // MARK: - In progress

    private func starting(_ provider: ProviderID) -> some View {
        UsageCard {
            Text(provider.displayName)
                .usageFont(.section)
                .foregroundStyle(colors.ink)
            HStack(spacing: 8) {
                ActivityMark(still: .dots)
                Text("Asking \(provider.displayName) for a code…")
                    .usageFont(.body)
                    .foregroundStyle(colors.muted)
            }
        }
    }

    private func waiting(_ provider: ProviderID, _ challenge: DeviceLoginChallenge) -> some View {
        UsageCard {
            Text("Enter this code")
                .usageFont(.section)
                .foregroundStyle(colors.ink)

            // Monospaced digits so the code does not reflow while it is being read aloud or
            // copied character by character, and selectable because typing it on another device
            // is the whole point.
            // The sheet's one lead figure: a code is a figure, so it is monospaced ink, not a
            // second brand colour.
            Text(verbatim: challenge.userCode)
                .tracking(1.2)
                .usageFont(.lead)
                .foregroundStyle(colors.ink)
                .textSelection(.enabled)
                .accessibilityLabel(spelledOut(challenge.userCode))

            Text(verbatim: challenge.verificationURI)
                .usageFont(.small)
                .foregroundStyle(colors.muted)

            HStack(spacing: 10) {
                Button(copied ? String(localized: "Copied") : String(localized: "Copy code")) {
                    UIPasteboard.general.string = challenge.userCode
                    copied = true
                }
                .buttonStyle(InkButtonStyle())

                Button("Open sign-in page") {
                    // The pre-filled page when the provider offers one, so the code does not have
                    // to be typed at all. Both URLs came from the provider and were checked
                    // against its own host before this screen vouched for either.
                    let target = challenge.verificationURIComplete ?? challenge.verificationURI
                    if let url = URL(string: target) { openURL(url) }
                }
                .buttonStyle(OutlineButtonStyle())
            }

            HStack(spacing: 8) {
                ActivityMark(still: .dots)
                Text("Waiting for you to approve it…")
                    .usageFont(.body)
                    .foregroundStyle(colors.muted)
            }

            Text("The browser opens \(provider.displayName)'s own sign-in page. This app never sees your password.")
                .usageFont(.small)
                .foregroundStyle(colors.faint)

            if DeviceLoginSupport.acceptsPastedKey(provider) {
                // The flow is the default because it costs the user nothing; the key is for
                // when the flow's account cannot reach the API yet.
                Button("Use an API key instead") {
                    login?.cancel()
                    stage = .awaitingKey(provider)
                }
                .buttonStyle(TextActionStyle())
            }
        }
        // On the clipboard before either button is pressed: the browser is opened for the user,
        // so the next thing they do is paste, and a code they have to go back and copy first is
        // a code they will type by hand.
        .onAppear {
            UIPasteboard.general.string = challenge.userCode
            copied = true
        }
    }

    /// The sign-in with nothing to drive: a key from the provider's console.
    ///
    /// Kimi Code's second way in, under its device flow, for the time before Moonshot has
    /// allowlisted this app's name on the coding API (see docs/providers-kimi.md), and the only
    /// way in for Z.ai and OpenCode Go.
    private func awaitingKey(_ provider: ProviderID) -> some View {
        UsageCard {
            Text("Paste your \(provider.displayName) key")
                .usageFont(.section)
                .foregroundStyle(colors.ink)

            Text("Create a key in your \(provider.displayName) console and paste it here. It is stored on this device only, in the keychain with every other account.")
                .usageFont(.body)
                .foregroundStyle(colors.muted)

            if let region {
                Text(verbatim: DisplayText.regionLabel(region, for: provider))
                    .usageFont(.small)
                    .foregroundStyle(colors.faint)
            }

            TextField("API key", text: $pastedKey)
                .ledgerField()
                .autocorrectionDisabled()
                .textInputAutocapitalization(.never)

            HStack(spacing: 10) {
                Button("Connect") { submitKey(provider) }
                    .buttonStyle(InkButtonStyle())
                    .disabled(pastedKey.trimmingCharacters(in: .whitespaces).isEmpty)

                Button("Open console") {
                    let target = ProviderEndpoints.consoleURL(
                        for: provider, region: region ?? ProviderRegion.defaultRegion(for: provider))
                    if let target, let url = URL(string: target) { openURL(url) }
                }
                .buttonStyle(OutlineButtonStyle())
            }
        }
    }

    private func failure(_ provider: ProviderID, _ message: String) -> some View {
        UsageCard {
            HStack(spacing: 8) {
                Circle().fill(colors.negative).frame(width: 8, height: 8)
                    .accessibilityHidden(true)
                Text("Could not add \(provider.displayName)")
                    .usageFont(.section)
                    .foregroundStyle(colors.ink)
            }
            Text(verbatim: DisplayText.stored(message))
                .usageFont(.body)
                .foregroundStyle(colors.muted)
            HStack(spacing: 10) {
                Button("Try again") { start(provider) }
                    .buttonStyle(InkButtonStyle())
                if DeviceLoginSupport.acceptsPastedKey(provider) {
                    Button("Use an API key") {
                        login?.cancel()
                        stage = .awaitingKey(provider)
                    }
                    .buttonStyle(OutlineButtonStyle())
                }
            }
        }
    }

    /// Reads a code out character by character, so VoiceOver does not pronounce "ABCD" as a word
    /// or run two groups together.
    private func spelledOut(_ code: String) -> String {
        code.map(String.init).joined(separator: " ")
    }

    // MARK: - Driving the flow

    /// A regional provider asks for its region first; the rest start straight away.
    private func select(_ provider: ProviderID) {
        if ProviderRegion.options(for: provider).isEmpty {
            region = nil
            start(provider)
        } else {
            stage = .choosingRegion(provider)
        }
    }

    /// Three styles now, so a binary check would call the pasted-key row a browser sign-in.
    private func signInHint(for provider: ProviderID) -> String {
        switch DeviceLoginSupport.style(for: provider) {
        case .deviceCode: return String(localized: "Shows a code to approve in your browser.")
        case .loopbackRedirect: return String(localized: "Opens the provider's sign-in page.")
        case .pastedKey: return String(localized: "Needs a key from the provider's console.")
        }
    }

    private func submitKey(_ provider: ProviderID) {
        let key = pastedKey
        login?.cancel()
        stage = .starting(provider)

        login = Task {
            guard let container = UsageStore.sharedContainer else {
                stage = .failed(provider, String(localized: "This build cannot reach its shared storage."))
                return
            }
            do {
                _ = try await container.completePastedKeyLogin(provider: provider, key: key, region: region)
                await store.load()
                await store.refresh()
                dismiss()
            } catch is CancellationError {
                // The sheet was closed.
            } catch {
                stage = .failed(provider, error.localizedDescription)
            }
        }
    }

    private func start(_ provider: ProviderID) {
        login?.cancel()
        stage = .starting(provider)

        login = Task {
            guard let container = UsageStore.sharedContainer else {
                stage = .failed(provider, String(localized: "This build cannot reach its shared storage."))
                return
            }
            do {
                switch DeviceLoginSupport.style(for: provider) {
                case .deviceCode:
                    let challenge = try await container.beginLogin(provider: provider, region: region)
                    stage = .waiting(provider, challenge)
                    // Blocks until the user approves, the code expires, or the provider refuses.
                    _ = try await container.completeLogin(
                        provider: provider, challenge: challenge)

                case .pastedKey:
                    // Parks here. The key arrives through `submitKey`, which runs its own
                    // task, so this one simply ends rather than holding the sheet open.
                    stage = .awaitingKey(provider)
                    return

                case .loopbackRedirect:
                    // The listener is bound INSIDE `authorize`, before the browser opens: a
                    // provider that redirects promptly would otherwise find nothing listening.
                    let challenge = try await container.beginLoopbackLogin(provider: provider)
                    do {
                        let code = try await loopback.authorize(challenge)
                        _ = try await container.completeLoopbackLogin(
                            code: code, challenge: challenge)
                    } catch LoopbackListener.ListenError.portUnavailable where provider == .codex {
                        try Task.checkCancellation()
                        // The CLI's port is taken, and Codex alone has a second way in: its
                        // device flow needs no port at all, at the price of a code to carry
                        // into the browser — the step the browser flow exists to remove.
                        let fallback = try await container.beginLogin(provider: provider)
                        stage = .waiting(provider, fallback)
                        _ = try await container.completeLogin(
                            provider: provider, challenge: fallback)
                    }
                }

                // Straight into a refresh: an account that appears with no numbers looks like it
                // failed, when in fact nothing has asked yet.
                await store.load()
                await store.refresh()
                dismiss()
            } catch is CancellationError {
                // Dismissing only the browser leaves this sheet open. A cancelled
                // task belongs to a closed sheet or an older attempt instead.
                if !Task.isCancelled { stage = .choosing }
            } catch {
                stage = .failed(provider, error.localizedDescription)
            }
        }
    }

    #if DEBUG
    private struct SignInMotionAccessibilityFixture: View {
        @State private var stage: Stage = .choosing
        @State private var holdExit = false
        @State private var reversal = Reversal.idle
        @StateObject private var retained = RetainedAction()

        private enum Reversal: Equatable {
            case idle, exiting, returning(from: Double), resumed, settled
        }

        private func observeReversal(_ opacity: Double, leaving: Bool) {
            switch reversal {
            case .exiting where leaving && opacity > 0.2 && opacity < 0.8:
                reversal = .returning(from: opacity)
                stage = .choosing
            case .returning(let exit) where !leaving && opacity > exit && opacity < 0.95:
                reversal = .resumed
            case .resumed where !leaving && opacity == 1:
                reversal = .settled
            default: break
            }
        }

        @MainActor
        private final class RetainedAction: ObservableObject {
            @Published var call: (@MainActor () -> Void)?
        }

        var body: some View {
            VStack(spacing: 8) {
                AddAccountSheet(stage: $stage, captureStep: { observeReversal($0, leaving: $1) },
                                holdExit: holdExit) { [weak retained] action in
                    Task { @MainActor in retained?.call = action }
                }
                HStack {
                    if holdExit {
                        Button { holdExit = false } label: { Text(verbatim: "Release exit") }
                        if let action = retained.call { Button { action() } label: { Text(verbatim: "Activate retained Kimi") } }
                    } else {
                        if retained.call != nil {
                            Button {
                                holdExit = true
                                stage = .awaitingKey(.opencode)
                            } label: { Text(verbatim: "Request key step") }
                        }
                        Button { stage = .choosing } label: { Text(verbatim: "Restore chooser") }
                    }
                }
                Button {
                    reversal = .exiting
                    stage = .awaitingKey(.opencode)
                } label: {
                    Text(verbatim: reversal == .settled ? "Reversal settled" : "Reverse at intermediate")
                }
                .disabled(holdExit || reversal != .idle || stage != .choosing || retained.call == nil)
                .padding(.bottom, 8)
            }
            .onDisappear { retained.call = nil }
        }
    }
    #endif
}

// PlainButtonStyle can fade the enabled state independently of the card's fade.
private struct ProviderPickerRowStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .opacity(configuration.isPressed ? 0.7 : 1)
            .pressRelease(isPressed: configuration.isPressed)
    }
}

/// iOS 16 meldet das Ende einer Animation über ihren tatsächlichen Animatable-Wert.
private struct SignInStepOpacity: AnimatableModifier {
    // SwiftUI interpoliert diese lokale Wertkopie außerhalb des Main Actors.
    nonisolated(unsafe) var value: Double
    let capturesFrames: Bool
    let onFrame: @MainActor @Sendable (Double) -> Void

    nonisolated var animatableData: Double {
        get { value }
        set {
            value = newValue
            guard newValue == 0 || capturesFrames else { return }
            let report = onFrame
            let sample = newValue
            // Zustandsänderungen dürfen nicht innerhalb des laufenden View-Updates passieren.
            Task { @MainActor in report(sample) }
        }
    }

    func body(content: Content) -> some View { content.opacity(value) }
}
