import UIKit

@main
final class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?
    ) -> Bool {
        let arguments = ProcessInfo.processInfo.arguments
        let scenario = arguments.first { $0.hasPrefix("scenario=") }?.replacingOccurrences(of: "scenario=", with: "") ?? "navigation"
        let language = arguments.first { $0.hasPrefix("language=") }?.replacingOccurrences(of: "language=", with: "") == "it" ? "it" : "en"
        let controller = FixtureViewController(scenario: scenario, language: language)
        window = UIWindow(frame: UIScreen.main.bounds)
        window?.rootViewController = controller
        window?.makeKeyAndVisible()
        return true
    }
}

final class FixtureViewController: UIViewController {
    private let scenario: String
    private let language: String
    private let stack = UIStackView()

    init(scenario: String, language: String) {
        self.scenario = scenario
        self.language = language
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { fatalError("init(coder:) is unsupported") }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .systemBackground
        stack.axis = .vertical
        stack.spacing = 16
        stack.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(stack)
        NSLayoutConstraint.activate([
            stack.leadingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.leadingAnchor, constant: 24),
            stack.trailingAnchor.constraint(equalTo: view.safeAreaLayoutGuide.trailingAnchor, constant: -24),
            stack.topAnchor.constraint(equalTo: view.safeAreaLayoutGuide.topAnchor, constant: 24)
        ])
        addButton(id: "start", title: text("start"))
        switch scenario {
        case "navigation":
            let email = UITextField()
            email.accessibilityIdentifier = "email"
            email.placeholder = "Email"
            email.borderStyle = .roundedRect
            stack.addArrangedSubview(email)
            addButton(id: "continue", title: text("continue")) { [weak self] in
                self?.stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
                self?.addLabel(id: "done", title: self?.text("done") ?? "Done")
            }
        case "appear":
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { [weak self] in self?.addLabel(id: "done", title: self?.text("done") ?? "Done") }
        case "disappear":
            let loading = addLabel(id: "loading", title: text("loading"))
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.75) { loading.removeFromSuperview() }
        case "ambiguous":
            addButton(id: "continue_primary", title: text("continue"))
            addButton(id: "continue_secondary", title: text("continue"))
        case "error":
            addLabel(id: "error", title: text("error"))
        case "missing": break
        default: break
        }
    }

    @discardableResult private func addLabel(id: String, title: String) -> UILabel {
        let label = UILabel()
        label.text = title
        label.accessibilityIdentifier = id
        stack.addArrangedSubview(label)
        return label
    }

    private func addButton(id: String, title: String, action: (() -> Void)? = nil) {
        let button = UIButton(type: .system)
        button.setTitle(title, for: .normal)
        button.accessibilityIdentifier = id
        button.heightAnchor.constraint(greaterThanOrEqualToConstant: 44).isActive = true
        button.addAction(UIAction { _ in action?() }, for: .touchUpInside)
        stack.addArrangedSubview(button)
    }

    private func text(_ key: String) -> String {
        let values = language == "it"
            ? ["start": "Avvia", "continue": "Continua", "loading": "Caricamento", "done": "Fatto", "error": "Errore"]
            : ["start": "Start", "continue": "Continue", "loading": "Loading", "done": "Done", "error": "Error"]
        return values[key] ?? key
    }
}
