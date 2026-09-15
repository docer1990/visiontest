import XCTest

/// Bridge providing XCUITest automation operations.
/// Mirrors BaseUiAutomatorBridge.kt from the Android automation server.
///
/// Uses the Springboard app as coordinate space for taps/swipes so that
/// interactions work regardless of which app is in the foreground.
/// For hierarchy/element queries, targets a specific app by bundleId when provided.
class XCUITestBridge {

    /// Springboard provides a screen-level coordinate space for taps/swipes
    private let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    /// Cached screen size to avoid expensive screenshot calls
    private var cachedScreenSize: CGSize?
    /// Orientation when screen size was last cached
    private var cachedOrientation: UIDeviceOrientation?
    /// Cache of XCUIApplication instances by bundleId
    private var appCache: [String: XCUIApplication] = [:]
    private let interactionTargetOverride: ((String?) -> XCUIApplication)?

    init(interactionTargetOverride: ((String?) -> XCUIApplication)? = nil) {
        self.interactionTargetOverride = interactionTargetOverride
    }

    /// Returns the XCUIApplication for the given bundleId, or springboard if nil.
    private func queryTarget(bundleId: String?) -> XCUIApplication {
        guard let bundleId = bundleId, !bundleId.isEmpty else {
            return springboard
        }
        if let cached = appCache[bundleId] {
            return cached
        }
        let app = XCUIApplication(bundleIdentifier: bundleId)
        appCache[bundleId] = app
        return app
    }

    private func interactionTarget(bundleId: String?) -> XCUIApplication {
        if let interactionTargetOverride {
            return interactionTargetOverride(bundleId)
        }
        let explicit = bundleId.flatMap { $0.isEmpty ? nil : queryTarget(bundleId: $0) }
        return selectInteractionTarget(
            explicit: explicit,
            discovered: discoverActiveApplication(),
            cached: appCache.values.first(where: { $0.state == .runningForeground }),
            system: springboard
        )
    }

    private func discoverActiveApplication() -> XCUIApplication? {
        guard let accessibility = objectReturned(
            by: "accessibilityInterface",
            from: XCUIDevice.shared as NSObject
        ) as? NSObject,
              let activeElements = objectReturned(by: "activeApplications", from: accessibility) as? [NSObject],
              let systemElement = objectReturned(by: "systemApplication", from: accessibility) as? NSObject,
              let systemPid = processIdentifier(of: systemElement),
              let tracker = objectReturned(by: "applicationProcessTracker", from: accessibility) as? NSObject else {
            return nil
        }
        for element in activeElements {
            guard let pid = processIdentifier(of: element),
                  pid != systemPid,
                  let application = monitoredApplication(pid: pid, tracker: tracker) else {
                continue
            }
            return application
        }
        return nil
    }

    private func objectReturned(by selectorName: String, from object: NSObject) -> AnyObject? {
        let selector = NSSelectorFromString(selectorName)
        guard object.responds(to: selector) else { return nil }
        return object.perform(selector)?.takeUnretainedValue()
    }

    private func processIdentifier(of object: NSObject) -> Int32? {
        let selector = NSSelectorFromString("processIdentifier")
        guard object.responds(to: selector) else { return nil }
        typealias Implementation = @convention(c) (AnyObject, Selector) -> Int32
        let implementation = unsafeBitCast(object.method(for: selector), to: Implementation.self)
        return implementation(object, selector)
    }

    private func monitoredApplication(pid: Int32, tracker: NSObject) -> XCUIApplication? {
        let selector = NSSelectorFromString("monitoredApplicationWithProcessIdentifier:")
        guard tracker.responds(to: selector) else { return nil }
        typealias Implementation = @convention(c) (AnyObject, Selector, Int32) -> Unmanaged<AnyObject>?
        let implementation = unsafeBitCast(tracker.method(for: selector), to: Implementation.self)
        return implementation(tracker, selector, pid)?.takeUnretainedValue() as? XCUIApplication
    }

    /// Returns screen size, caching per orientation to avoid repeated screenshot calls.
    private func getScreenSize() -> CGSize {
        let currentOrientation = XCUIDevice.shared.orientation
        if let cached = cachedScreenSize, currentOrientation == cachedOrientation {
            return cached
        }
        let size = XCUIScreen.main.screenshot().image.size
        cachedScreenSize = size
        cachedOrientation = currentOrientation
        return size
    }

    // MARK: - UI Hierarchy

    /// Dumps the current UI hierarchy as XML, matching Android's format.
    /// Uses XCUITest's snapshot API for fast tree access.
    /// - Parameter bundleId: Bundle ID of the app to query. If nil, queries springboard.
    func dumpHierarchy(bundleId: String? = nil) -> UiHierarchyResult {
        do {
            let target = queryTarget(bundleId: bundleId)
            let snapshot = try target.snapshot()
            var xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            xml += "<hierarchy rotation=\"\(orientationToRotation())\">\n"
            dumpSnapshotRecursive(snapshot: snapshot, indent: "  ", xml: &xml)
            xml += "</hierarchy>\n"
            return UiHierarchyResult(success: true, hierarchy: xml, error: nil)
        } catch {
            return UiHierarchyResult(success: false, hierarchy: nil, error: error.localizedDescription)
        }
    }

    private func dumpSnapshotRecursive(snapshot: XCUIElementSnapshot, indent: String, xml: inout String) {
        let frame = snapshot.frame
        let bounds = boundsString(from: frame)
        let text = escapeXML(snapshot.label)
        let identifier = escapeXML(snapshot.identifier)
        let elementType = elementTypeName(snapshot.elementType)
        let value = escapeXML(snapshotValue(snapshot))

        xml += "\(indent)<node"
        xml += " text=\"\(text)\""
        xml += " resource-id=\"\(identifier)\""
        xml += " class=\"\(elementType)\""
        xml += " content-desc=\"\(identifier)\""
        xml += " enabled=\"\(snapshot.isEnabled)\""
        xml += " bounds=\"\(bounds)\""
        xml += " value=\"\(value)\""

        let children = snapshot.children
        if children.count > 0 {
            xml += ">\n"
            for child in children {
                dumpSnapshotRecursive(snapshot: child, indent: indent + "  ", xml: &xml)
            }
            xml += "\(indent)</node>\n"
        } else {
            xml += " />\n"
        }
    }

    // MARK: - Device Info

    func getDeviceInfo() -> DeviceInfoResult {
        let screenSize = getScreenSize()
        let rotation = orientationToRotation()
        let device = UIDevice.current

        return DeviceInfoResult(
            displayWidth: Int(screenSize.width),
            displayHeight: Int(screenSize.height),
            displayRotation: rotation,
            productName: device.model,
            osVersion: device.systemVersion,
            success: true,
            error: nil
        )
    }

    // MARK: - Screenshot

    /// Captures the current simulator display as a PNG and returns it base64-encoded.
    func screenshot() -> ScreenshotResult {
        let pngData = XCUIScreen.main.screenshot().pngRepresentation
        if pngData.isEmpty {
            return ScreenshotResult(success: false, pngBase64: nil, error: "Screenshot returned empty PNG data")
        }
        let base64 = pngData.base64EncodedString()
        return ScreenshotResult(success: true, pngBase64: base64, error: nil)
    }

    // MARK: - Tap

    /// Taps at absolute screen coordinates using the springboard coordinate space.
    func tapByCoordinates(x: Int, y: Int) -> OperationResult {
        let coordinate = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: CGFloat(x), dy: CGFloat(y)))
        coordinate.tap()
        return OperationResult(success: true, error: nil)
    }

    // MARK: - Press Home

    func pressHome() -> OperationResult {
        XCUIDevice.shared.press(.home)
        return OperationResult(success: true, error: nil)
    }

    // MARK: - Find Element

    /// Finds an element in the target app or springboard.
    /// - Parameter bundleId: Bundle ID of the app to query. If nil, queries springboard.
    func findElement(
        text: String? = nil,
        textContains: String? = nil,
        identifier: String? = nil,
        elementType: String? = nil,
        label: String? = nil,
        bundleId: String? = nil
    ) -> ElementResult {
        guard text != nil || textContains != nil || identifier != nil || label != nil ||
              elementType.flatMap({ xcuiElementType(from: $0) }) != nil else {
            return ElementResult(found: false, text: nil, identifier: nil, elementType: nil,
                                 label: nil, value: nil, bounds: nil, isEnabled: nil,
                                 error: "No selector provided")
        }
        guard let found = lookupElement(text: text, textContains: textContains, identifier: identifier,
                                        elementType: elementType, label: label, bundleId: bundleId) else {
            return ElementResult(found: false, text: nil, identifier: nil, elementType: nil,
                                 label: nil, value: nil, bounds: nil, isEnabled: nil, error: nil)
        }

        let frame = found.frame
        return ElementResult(
            found: true,
            text: found.label.isEmpty ? nil : found.label,
            identifier: found.identifier.isEmpty ? nil : found.identifier,
            elementType: elementTypeName(found.elementType),
            label: found.label.isEmpty ? nil : found.label,
            value: stringValue(of: found),
            bounds: boundsString(from: frame),
            isEnabled: found.isEnabled,
            error: nil
        )
    }

    /// Shared lookup preserves findElement's selector precedence and app scope.
    private func lookupElement(
        text: String?, textContains: String?, identifier: String?, elementType: String?,
        label: String?, bundleId: String?
    ) -> XCUIElement? {
        lookupElement(
            text: text,
            textContains: textContains,
            identifier: identifier,
            elementType: elementType,
            label: label,
            target: queryTarget(bundleId: bundleId)
        )
    }

    private func lookupElement(
        text: String?, textContains: String?, identifier: String?, elementType: String?,
        label: String?, target: XCUIApplication
    ) -> XCUIElement? {
        let element: XCUIElement?

        if let text = text {
            let predicate = NSPredicate(format: "label == %@ OR value == %@", text, text)
            let match = target.descendants(matching: .any).matching(predicate).firstMatch
            element = match.exists ? match : nil
        } else if let textContains = textContains {
            let predicate = NSPredicate(format: "label CONTAINS[c] %@ OR value CONTAINS[c] %@", textContains, textContains)
            let match = target.descendants(matching: .any).matching(predicate).firstMatch
            element = match.exists ? match : nil
        } else if let identifier = identifier {
            let match = target.descendants(matching: .any).matching(identifier: identifier).firstMatch
            element = match.exists ? match : nil
        } else if let elementType = elementType, let type = xcuiElementType(from: elementType) {
            let match = target.descendants(matching: type).firstMatch
            element = match.exists ? match : nil
        } else if let label = label {
            let predicate = NSPredicate(format: "label == %@", label)
            let match = target.descendants(matching: .any).matching(predicate).firstMatch
            element = match.exists ? match : nil
        } else {
            return nil
        }

        return element
    }

    func swipeOnElement(_ request: ElementSwipeRequest) -> OperationResult {
        let element = lookupElement(text: request.text, textContains: request.textContains,
                                    identifier: request.identifier, elementType: request.elementType,
                                    label: request.label, bundleId: request.bundleId)
        let screenFrame = CGRect(origin: .zero, size: getScreenSize())
        return performElementSwipe(
            frame: element?.frame,
            visibleFrame: screenFrame,
            direction: request.direction,
            speed: request.speed
        ) { start, end, duration in
            self.swipe(start: start, end: end, duration: duration)
        }
    }

    func tapOnElement(_ request: ElementTapRequest) -> OperationResult {
        var tappableElement: XCUIElement?
        return performElementTap(
            request: request,
            now: { ProcessInfo.processInfo.systemUptime },
            readiness: {
                tappableElement = nil
                guard let element = self.lookupElement(
                    text: request.text,
                    textContains: request.textContains,
                    identifier: request.identifier,
                    elementType: request.elementType,
                    label: request.label,
                    bundleId: request.bundleId
                ) else {
                    return .absent
                }
                guard isElementTapReady(
                    exists: element.exists,
                    isEnabled: element.isEnabled,
                    isHittable: element.isHittable
                ) else {
                    return .blocked
                }
                tappableElement = element
                return .ready
            },
            tap: { tappableElement?.tap() }
        )
    }

    func longPress(_ request: GestureRequest) -> OperationResult {
        performGesture(
            request,
            coordinateGesture: { $0.press(forDuration: 0.8) },
            elementGesture: { $0.press(forDuration: 0.8) }
        )
    }

    func doubleTap(_ request: GestureRequest) -> OperationResult {
        performGesture(
            request,
            coordinateGesture: { $0.doubleTap() },
            elementGesture: { $0.doubleTap() }
        )
    }

    private func performGesture(
        _ request: GestureRequest,
        coordinateGesture: (XCUICoordinate) -> Void,
        elementGesture: (XCUIElement) -> Void
    ) -> OperationResult {
        switch request.target {
        case .coordinates(let point):
            let screenBounds = CGRect(origin: .zero, size: getScreenSize())
            guard screenBounds.contains(point) else {
                return OperationResult(success: false, error: "Coordinates are outside the display")
            }
            let coordinate = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
                .withOffset(CGVector(dx: point.x, dy: point.y))
            coordinateGesture(coordinate)
            return OperationResult(success: true, error: nil)
        case .element(let selectors, let bundleId, let timeoutMs):
            let target = interactionTarget(bundleId: bundleId)
            let deadline = ProcessInfo.processInfo.systemUptime + Double(timeoutMs) / 1_000
            var observedBlocked = false
            while true {
                if let element = lookupElement(selectors: selectors, target: target) {
                    if element.exists && element.isEnabled && element.isHittable {
                        guard ProcessInfo.processInfo.systemUptime <= deadline else { break }
                        elementGesture(element)
                        return OperationResult(success: true, error: nil)
                    }
                    observedBlocked = true
                }
                let remaining = deadline - ProcessInfo.processInfo.systemUptime
                guard remaining > 0 else { break }
                RunLoop.current.run(until: Date(timeIntervalSinceNow: min(0.5, remaining)))
            }
            let status = observedBlocked ? "Element found but not actionable" : "Element not found"
            return OperationResult(success: false, error: "\(status) for \(selectors.description)")
        }
    }

    func inputText(_ request: TargetedInputRequest) -> OperationResult {
        guard let selectors = request.selectors, request.timeoutMs != nil else {
            return inputText(text: request.text, bundleId: request.bundleId)
        }
        var selectedElement: XCUIElement?
        let target = interactionTarget(bundleId: request.bundleId)
        return performTargetedInput(
            request: request,
            now: { ProcessInfo.processInfo.systemUptime },
            readiness: {
                selectedElement = nil
                guard let element = self.lookupElement(selectors: selectors, target: target) else {
                    return .absent
                }
                guard self.isEditable(element), element.exists, element.isEnabled, element.isHittable else {
                    return .blocked
                }
                selectedElement = element
                return .ready
            },
            tap: { selectedElement?.tap() },
            hasFocus: {
                guard let selectedElement else { return false }
                let focused = target.descendants(matching: .any)
                    .matching(NSPredicate(format: "hasKeyboardFocus == true")).firstMatch
                return focused.exists && focused.frame == selectedElement.frame
            },
            typeText: { selectedElement?.typeText(request.text) }
        )
    }

    func dismissKeyboard(bundleId: String?) -> OperationResult {
        let keyboard = interactionTarget(bundleId: bundleId).keyboards.firstMatch
        let wasVisible = keyboard.exists && keyboard.isHittable
        if wasVisible {
            keyboard.swipeDown()
            _ = keyboard.waitForNonExistence(timeout: 2)
        }
        return verifyKeyboardDismissal(wasVisible: wasVisible, isVisible: keyboard.exists)
    }

    func handleAlert(_ request: HandleAlertRequest) -> OperationResult {
        let appAlert = interactionTarget(bundleId: request.bundleId).alerts.firstMatch
        let systemAlert = springboard.alerts.firstMatch
        guard let source = selectAlertSource(appExists: appAlert.exists, systemExists: systemAlert.exists) else {
            return OperationResult(success: false, error: "No alert is visible")
        }
        let alert = source == .application ? appAlert : systemAlert
        let buttons = alert.buttons.allElementsBoundByIndex
        let descriptions = buttons.map {
            AlertButton(label: $0.label, actionable: $0.exists && $0.isEnabled && $0.isHittable)
        }
        if let label = request.buttonLabel,
           let requested = descriptions.first(where: { $0.label == label }),
           !requested.actionable {
            return OperationResult(success: false, error: "Requested alert button is blocked")
        }
        guard let selected = selectAlertButton(descriptions, action: request.action, label: request.buttonLabel) else {
            let error = request.buttonLabel == nil ? "No actionable alert button" : "Requested alert button not found"
            return OperationResult(success: false, error: error)
        }
        guard let button = buttons.first(where: { $0.label == selected.label && $0.isEnabled && $0.isHittable }) else {
            return OperationResult(success: false, error: "Requested alert button is blocked")
        }
        button.tap()
        return OperationResult(success: true, error: nil, message: "Tapped alert button '\(selected.label)'")
    }

    private func lookupElement(selectors: ElementSelectors, bundleId: String?) -> XCUIElement? {
        lookupElement(selectors: selectors, target: queryTarget(bundleId: bundleId))
    }

    private func lookupElement(selectors: ElementSelectors, target: XCUIApplication) -> XCUIElement? {
        lookupElement(
            text: selectors.text,
            textContains: selectors.textContains,
            identifier: selectors.identifier,
            elementType: selectors.elementType,
            label: selectors.label,
            target: target
        )
    }

    private func isEditable(_ element: XCUIElement) -> Bool {
        [.textField, .secureTextField, .textView, .searchField].contains(element.elementType)
    }

    // MARK: - Interactive Elements

    /// Collects interactive elements using snapshot API for speed.
    /// Takes a single snapshot of the entire tree, then filters in-memory.
    /// - Parameter bundleId: Bundle ID of the app to query. If nil, queries springboard.
    func getInteractiveElements(includeDisabled: Bool = false, bundleId: String? = nil) -> InteractiveElementsResult {
        do {
            let target = queryTarget(bundleId: bundleId)
            let snapshot = try target.snapshot()
            var elements: [InteractiveElement] = []
            collectInteractiveFromSnapshot(snapshot, elements: &elements, includeDisabled: includeDisabled)

            return InteractiveElementsResult(
                success: true,
                elements: elements,
                count: elements.count,
                error: nil
            )
        } catch {
            return InteractiveElementsResult(
                success: false,
                elements: [],
                count: 0,
                error: error.localizedDescription
            )
        }
    }

    private static let interactiveTypeSet: Set<XCUIElement.ElementType> = [
        .button, .link, .textField, .secureTextField,
        .switch, .slider, .stepper, .picker,
        .segmentedControl, .toggle, .tab,
        .searchField, .textView
    ]

    private static let layoutContainerTypeSet: Set<XCUIElement.ElementType> = [
        .other, .group, .scrollView, .table, .collectionView,
        .window, .application
    ]

    private func collectInteractiveFromSnapshot(
        _ snapshot: XCUIElementSnapshot,
        elements: inout [InteractiveElement],
        includeDisabled: Bool
    ) {
        if shouldIncludeSnapshot(snapshot, includeDisabled: includeDisabled) {
            let frame = snapshot.frame
            if frame.width > 0 && frame.height > 0 {
                let labelText = snapshot.label.isEmpty ? nil : snapshot.label
                elements.append(InteractiveElement(
                    text: labelText,
                    identifier: snapshot.identifier.isEmpty ? nil : snapshot.identifier,
                    elementType: elementTypeName(snapshot.elementType),
                    label: labelText,
                    value: snapshotValue(snapshot),
                    bounds: boundsString(from: frame),
                    centerX: Int(frame.midX),
                    centerY: Int(frame.midY),
                    isEnabled: snapshot.isEnabled
                ))
            }
        }

        for child in snapshot.children {
            collectInteractiveFromSnapshot(child, elements: &elements, includeDisabled: includeDisabled)
        }
    }

    private func shouldIncludeSnapshot(_ snapshot: XCUIElementSnapshot, includeDisabled: Bool) -> Bool {
        if !includeDisabled && !snapshot.isEnabled { return false }

        let frame = snapshot.frame
        guard frame.width > 0, frame.height > 0 else { return false }

        let type = snapshot.elementType

        if XCUITestBridge.interactiveTypeSet.contains(type) {
            return true
        }

        if XCUITestBridge.layoutContainerTypeSet.contains(type) {
            return !snapshot.label.isEmpty || !snapshot.identifier.isEmpty
        }

        if type == .staticText || type == .image {
            return !snapshot.label.isEmpty || !snapshot.identifier.isEmpty
        }

        if type == .cell {
            return true
        }

        if !snapshot.label.isEmpty || !snapshot.identifier.isEmpty {
            return true
        }

        return false
    }

    // MARK: - Swipe by Coordinates

    func swipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: TimeInterval) -> OperationResult {
        return swipe(start: CGPoint(x: startX, y: startY), end: CGPoint(x: endX, y: endY), duration: duration)
    }

    private func swipe(start: CGPoint, end: CGPoint, duration: TimeInterval) -> OperationResult {
        let startCoord = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: start.x, dy: start.y))
        let endCoord = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0, dy: 0))
            .withOffset(CGVector(dx: end.x, dy: end.y))

        let dx = end.x - start.x
        let dy = end.y - start.y
        let distance = sqrt(dx * dx + dy * dy)
        let velocity = max(distance / CGFloat(duration), 10.0) // floor at 10 pts/sec
        startCoord.press(forDuration: 0.05, thenDragTo: endCoord, withVelocity: XCUIGestureVelocity(rawValue: velocity), thenHoldForDuration: 0)

        return OperationResult(success: true, error: nil)
    }

    // MARK: - Swipe by Direction

    func swipeByDirection(
        direction: SwipeDirection,
        distance: SwipeDistance = .medium,
        speed: SwipeSpeed = .normal
    ) -> OperationResult {
        let screenSize = getScreenSize()
        let width = Int(screenSize.width)
        let height = Int(screenSize.height)

        let centerX = width / 2
        let centerY = height / 2
        let distancePercent = distance.percent

        let startX: Int, startY: Int, endX: Int, endY: Int

        switch direction {
        case .up:
            let offsetY = Int(Double(height) * distancePercent / 2)
            startX = centerX; startY = centerY + offsetY
            endX = centerX; endY = centerY - offsetY
        case .down:
            let offsetY = Int(Double(height) * distancePercent / 2)
            startX = centerX; startY = centerY - offsetY
            endX = centerX; endY = centerY + offsetY
        case .left:
            let offsetX = Int(Double(width) * distancePercent / 2)
            startX = centerX + offsetX; startY = centerY
            endX = centerX - offsetX; endY = centerY
        case .right:
            let offsetX = Int(Double(width) * distancePercent / 2)
            startX = centerX - offsetX; startY = centerY
            endX = centerX + offsetX; endY = centerY
        }

        return swipe(startX: startX, startY: startY, endX: endX, endY: endY, duration: speed.duration)
    }
    
    // MARK: - Input Text
    
    /// Types text into the currently focused element of the target application using the keyboard.
    /// The `bundleId` selects an explicit app. Without it, input targets the active app.
    /// The caller should ensure a text field in the target app is already focused.
    func inputText(text: String, bundleId: String? = nil) -> OperationResult {
        let target = interactionTarget(bundleId: bundleId)

        // Find the focused text input element to avoid cross-process keyboard relay.
        // Typing on the focused element directly avoids the _UIRemoteKeyboards protocol
        // which crashes on iOS 26.1 simulator (KERN_INVALID_ADDRESS in UIKeyboard).
        let focusedPredicate = NSPredicate(format: "hasKeyboardFocus == true")
        let focusedElement = target.descendants(matching: .any).matching(focusedPredicate).firstMatch

        if focusedElement.exists {
            focusedElement.typeText(text)
        } else {
            // When no focused element is found, avoid typing on Springboard.
            // Require callers to specify a bundleId (or focus a field) instead.
            if bundleId == nil || bundleId?.isEmpty == true {
                return OperationResult(
                    success: false,
                    error: "No focused text input element found for inputText. " +
                           "Ensure a text field is focused or provide a bundleId for the target app."
                )
            }
            // Fallback: type on the target app itself (not Springboard), which stays in-process.
            target.typeText(text)
        }

        return OperationResult(success: true, error: nil)
    }

    // MARK: - Helpers

    internal func orientationToRotation() -> Int {
        switch XCUIDevice.shared.orientation {
        case .portrait: return 0
        case .landscapeLeft: return 1
        case .portraitUpsideDown: return 2
        case .landscapeRight: return 3
        default: return 0
        }
    }

    private func stringValue(of element: XCUIElement) -> String? {
        guard let value = element.value else { return nil }
        let str = "\(value)"
        return str.isEmpty ? nil : str
    }

    private func snapshotValue(_ snapshot: XCUIElementSnapshot) -> String? {
        guard let value = snapshot.value else { return nil }
        let str = "\(value)"
        return str.isEmpty ? nil : str
    }

    /// Maps XCUIElement.ElementType to a human-readable string name.
    func elementTypeName(_ type: XCUIElement.ElementType) -> String {
        switch type {
        case .any: return "Any"
        case .other: return "Other"
        case .application: return "Application"
        case .group: return "Group"
        case .window: return "Window"
        case .sheet: return "Sheet"
        case .drawer: return "Drawer"
        case .alert: return "Alert"
        case .dialog: return "Dialog"
        case .button: return "Button"
        case .radioButton: return "RadioButton"
        case .radioGroup: return "RadioGroup"
        case .checkBox: return "CheckBox"
        case .disclosureTriangle: return "DisclosureTriangle"
        case .popUpButton: return "PopUpButton"
        case .comboBox: return "ComboBox"
        case .menuButton: return "MenuButton"
        case .toolbarButton: return "ToolbarButton"
        case .popover: return "Popover"
        case .keyboard: return "Keyboard"
        case .key: return "Key"
        case .navigationBar: return "NavigationBar"
        case .tabBar: return "TabBar"
        case .tabGroup: return "TabGroup"
        case .toolbar: return "Toolbar"
        case .statusBar: return "StatusBar"
        case .table: return "Table"
        case .tableRow: return "TableRow"
        case .tableColumn: return "TableColumn"
        case .outline: return "Outline"
        case .outlineRow: return "OutlineRow"
        case .browser: return "Browser"
        case .collectionView: return "CollectionView"
        case .slider: return "Slider"
        case .pageIndicator: return "PageIndicator"
        case .progressIndicator: return "ProgressIndicator"
        case .activityIndicator: return "ActivityIndicator"
        case .segmentedControl: return "SegmentedControl"
        case .picker: return "Picker"
        case .pickerWheel: return "PickerWheel"
        case .switch: return "Switch"
        case .toggle: return "Toggle"
        case .link: return "Link"
        case .image: return "Image"
        case .icon: return "Icon"
        case .searchField: return "SearchField"
        case .scrollView: return "ScrollView"
        case .scrollBar: return "ScrollBar"
        case .staticText: return "StaticText"
        case .textField: return "TextField"
        case .secureTextField: return "SecureTextField"
        case .datePicker: return "DatePicker"
        case .textView: return "TextView"
        case .menu: return "Menu"
        case .menuItem: return "MenuItem"
        case .menuBar: return "MenuBar"
        case .menuBarItem: return "MenuBarItem"
        case .map: return "Map"
        case .webView: return "WebView"
        case .incrementArrow: return "IncrementArrow"
        case .decrementArrow: return "DecrementArrow"
        case .timeline: return "Timeline"
        case .ratingIndicator: return "RatingIndicator"
        case .valueIndicator: return "ValueIndicator"
        case .splitGroup: return "SplitGroup"
        case .splitter: return "Splitter"
        case .relevanceIndicator: return "RelevanceIndicator"
        case .colorWell: return "ColorWell"
        case .helpTag: return "HelpTag"
        case .matte: return "Matte"
        case .dockItem: return "DockItem"
        case .ruler: return "Ruler"
        case .rulerMarker: return "RulerMarker"
        case .grid: return "Grid"
        case .levelIndicator: return "LevelIndicator"
        case .cell: return "Cell"
        case .layoutArea: return "LayoutArea"
        case .layoutItem: return "LayoutItem"
        case .handle: return "Handle"
        case .stepper: return "Stepper"
        case .tab: return "Tab"
        case .touchBar: return "TouchBar"
        case .statusItem: return "StatusItem"
        @unknown default: return "Unknown"
        }
    }

    /// Maps a string element type name back to XCUIElement.ElementType.
    private func xcuiElementType(from name: String) -> XCUIElement.ElementType? {
        let lowercased = name.lowercased()
        switch lowercased {
        case "button": return .button
        case "statictext": return .staticText
        case "textfield": return .textField
        case "securetextfield": return .secureTextField
        case "image": return .image
        case "switch": return .switch
        case "slider": return .slider
        case "link": return .link
        case "cell": return .cell
        case "table": return .table
        case "collectionview": return .collectionView
        case "scrollview": return .scrollView
        case "tab": return .tab
        case "navigationbar": return .navigationBar
        case "tabbar": return .tabBar
        case "toolbar": return .toolbar
        case "searchfield": return .searchField
        case "textview": return .textView
        case "picker": return .picker
        case "stepper": return .stepper
        case "segmentedcontrol": return .segmentedControl
        case "alert": return .alert
        case "webview": return .webView
        default: return nil
        }
    }
}
