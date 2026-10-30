import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxUI;
import javax.swing.plaf.basic.BasicComboPopup;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.ComboPopup;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.ActionListener;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Arc2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;

/* =====================================================================
 *  BUSINESS LOGIC  (BigDecimal money + saving/loading support)
 * ===================================================================== */

/* ===================== Custom Exception ===================== */
class InsufficientFundsException extends Exception {
    public InsufficientFundsException(String message) { super(message); }
}

/* ===================== Transaction ===================== */
class Transaction {
    private static int counter = 1000;           // shared id generator
    private final int id;
    private final String type;
    private final BigDecimal amount;
    private final BigDecimal balanceAfter;
    private final String time;

    public Transaction(String type, BigDecimal amount, BigDecimal balanceAfter) {
        this.id = ++counter;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.time = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    /** Used when loading saved data: keeps the original id and timestamp. */
    Transaction(int id, String time, String type, BigDecimal amount, BigDecimal balanceAfter) {
        this.id = id;
        this.time = time;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        counter = Math.max(counter, id);
    }

    public int getId() { return id; }
    public String getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public String getTime() { return time; }

    public Object[] toRow() {
        return new Object[]{id, time, type, String.format("%.2f", amount), String.format("%.2f", balanceAfter)};
    }
}

/* ===================== Customer ===================== */
class Customer {
    private static int counter = 0;
    private final String id;
    private String name;
    private String phone;
    private final List<Account> accounts = new ArrayList<>();

    public Customer(String name, String phone) {
        this.id = "C" + String.format("%03d", ++counter);
        this.name = name;
        this.phone = phone;
    }

    private Customer(String id, String name, String phone) {
        this.id = id;
        this.name = name;
        this.phone = phone;
    }

    /** Used when loading saved data: keeps the original id. */
    static Customer restore(String id, String name, String phone) {
        counter = Math.max(counter, Integer.parseInt(id.substring(1)));
        return new Customer(id, name, phone);
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public List<Account> getAccounts() { return accounts; }
    public void addAccount(Account a) { accounts.add(a); }

    @Override public String toString() { return id + " - " + name; }
}

/* ===================== Abstraction: Account ===================== */
abstract class Account {
    private static int counter = 10000;
    private final String accountNumber;
    private final Customer owner;
    private BigDecimal balance = BigDecimal.ZERO;   // Encapsulation: private state (exact decimal money)
    private final List<Transaction> history = new ArrayList<>();

    protected Account(Customer owner, BigDecimal initialDeposit) {
        this.accountNumber = "ACC" + (++counter);
        this.owner = owner;
        if (initialDeposit.signum() > 0) {
            balance = initialDeposit;
            history.add(new Transaction("OPENING DEPOSIT", initialDeposit, balance));
        }
    }

    /** Used when loading saved data: keeps the original number and balance. */
    protected Account(String accountNumber, Customer owner, BigDecimal balance) {
        this.accountNumber = accountNumber;
        this.owner = owner;
        this.balance = balance;
        counter = Math.max(counter, Integer.parseInt(accountNumber.substring(3)));
    }

    void restoreTransaction(Transaction t) { history.add(t); }

    // ---- Encapsulation: controlled access ----
    public String getAccountNumber() { return accountNumber; }
    public Customer getOwner() { return owner; }
    public BigDecimal getBalance() { return balance; }
    public List<Transaction> getHistory() { return new ArrayList<>(history); } // copy

    public void deposit(BigDecimal amount) {
        if (amount.signum() <= 0) throw new IllegalArgumentException("Deposit must be positive.");
        balance = balance.add(amount);
        history.add(new Transaction("DEPOSIT", amount, balance));
    }

    public void withdraw(BigDecimal amount) throws InsufficientFundsException {
        if (amount.signum() <= 0) throw new IllegalArgumentException("Withdrawal must be positive.");
        if (!canWithdraw(amount))
            throw new InsufficientFundsException("Withdrawal denied by " + getType() + " rules.");
        balance = balance.subtract(amount);
        history.add(new Transaction("WITHDRAWAL", amount, balance));
    }

    // Used by subclasses for interest / fees
    protected void applyAdjustment(String label, BigDecimal amount) {
        balance = balance.add(amount);
        history.add(new Transaction(label, amount.abs(), balance));
    }

    // ---- Abstract methods: each account type decides ----
    protected abstract boolean canWithdraw(BigDecimal amount);
    public abstract String getType();
    public abstract String monthEnd();           // polymorphic behaviour

    @Override
    public String toString() {
        return accountNumber + " (" + getType() + ") - " + owner.getName();
    }
}

/* ===================== Inheritance: SavingsAccount ===================== */
class SavingsAccount extends Account {
    private static final BigDecimal MIN_BALANCE = new BigDecimal("100");
    private static final BigDecimal INTEREST_RATE = new BigDecimal("0.03");   // 3% yearly

    public SavingsAccount(Customer owner, BigDecimal initialDeposit) { super(owner, initialDeposit); }
    SavingsAccount(String number, Customer owner, BigDecimal balance) { super(number, owner, balance); }

    @Override protected boolean canWithdraw(BigDecimal amount) {
        return getBalance().subtract(amount).compareTo(MIN_BALANCE) >= 0;     // must keep minimum balance
    }
    @Override public String getType() { return "Savings"; }
    @Override public String monthEnd() {
        BigDecimal interest = getBalance().multiply(INTEREST_RATE)
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        if (interest.signum() <= 0) return "No interest earned this month (balance too low).";
        applyAdjustment("INTEREST", interest);
        return String.format("Interest of %.2f added.", interest);
    }
}

/* ===================== Inheritance: CheckingAccount ===================== */
class CheckingAccount extends Account {
    private static final BigDecimal OVERDRAFT_LIMIT = new BigDecimal("500");
    private static final BigDecimal MONTHLY_FEE = new BigDecimal("5");

    public CheckingAccount(Customer owner, BigDecimal initialDeposit) { super(owner, initialDeposit); }
    CheckingAccount(String number, Customer owner, BigDecimal balance) { super(number, owner, balance); }

    @Override protected boolean canWithdraw(BigDecimal amount) {
        return getBalance().add(OVERDRAFT_LIMIT).compareTo(amount) >= 0; // overdraft allowed
    }
    @Override public String getType() { return "Checking"; }
    @Override public String monthEnd() {
        applyAdjustment("MONTHLY FEE", MONTHLY_FEE.negate());
        return String.format("Monthly fee of %.2f charged.", MONTHLY_FEE);
    }
}

/* ===================== Bank (manages everything) ===================== */
class Bank {
    private final Map<String, Customer> customers = new LinkedHashMap<>();
    private final Map<String, Account> accounts = new LinkedHashMap<>();

    public Customer addCustomer(String name, String phone) {
        Customer c = new Customer(name, phone);
        customers.put(c.getId(), c);
        return c;
    }

    public Account openAccount(Customer c, String type, BigDecimal initial) {
        Account a = type.equals("Savings") ? new SavingsAccount(c, initial)
                                           : new CheckingAccount(c, initial);
        c.addAccount(a);
        accounts.put(a.getAccountNumber(), a);
        return a;
    }

    // ---- used only when loading saved data ----
    Customer restoreCustomer(String id, String name, String phone) {
        Customer c = Customer.restore(id, name, phone);
        customers.put(c.getId(), c);
        return c;
    }

    Account restoreAccount(String type, String number, String ownerId, BigDecimal balance) {
        Customer c = customers.get(ownerId);
        if (c == null) throw new IllegalStateException("Unknown customer " + ownerId);
        Account a = type.equals("Savings") ? new SavingsAccount(number, c, balance)
                                           : new CheckingAccount(number, c, balance);
        c.addAccount(a);
        accounts.put(number, a);
        return a;
    }

    Account findAccount(String number) { return accounts.get(number); }

    void clear() { customers.clear(); accounts.clear(); }

    public List<Customer> getCustomers() { return new ArrayList<>(customers.values()); }
    public List<Account> getAccounts() { return new ArrayList<>(accounts.values()); }
}

/* ===================== Persistence (plain tab-separated text file) ===================== */
final class DataStore {
    private final Path file;

    DataStore(Path file) { this.file = file; }

    static Path defaultPath() {
        return Paths.get(System.getProperty("user.home"), "BankFlow", "bankflow-data.tsv");
    }

    Path getFile() { return file; }
    boolean exists() { return Files.exists(file); }

    void save(Bank bank) throws IOException {
        List<String> lines = new ArrayList<>();
        lines.add("#BANKFLOW\t1");
        for (Customer c : bank.getCustomers())
            lines.add(join("C", c.getId(), c.getName(), c.getPhone()));
        for (Account a : bank.getAccounts())
            lines.add(join("A", a.getType(), a.getAccountNumber(), a.getOwner().getId(),
                    a.getBalance().toPlainString()));
        for (Account a : bank.getAccounts())
            for (Transaction t : a.getHistory())
                lines.add(join("T", a.getAccountNumber(), String.valueOf(t.getId()), t.getTime(), t.getType(),
                        t.getAmount().toPlainString(), t.getBalanceAfter().toPlainString()));

        Files.createDirectories(file.getParent());
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");     // write-then-rename: no half-written files
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    void load(Bank bank) throws IOException {
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] f = line.split("\t", -1);
            for (int i = 0; i < f.length; i++) f[i] = unescape(f[i]);
            switch (f[0]) {
                case "C":
                    bank.restoreCustomer(f[1], f[2], f[3]);
                    break;
                case "A":
                    bank.restoreAccount(f[1], f[2], f[3], new BigDecimal(f[4]));
                    break;
                case "T": {
                    Account a = bank.findAccount(f[1]);
                    if (a == null) throw new IOException("Transaction for unknown account " + f[1]);
                    a.restoreTransaction(new Transaction(Integer.parseInt(f[2]), f[3], f[4],
                            new BigDecimal(f[5]), new BigDecimal(f[6])));
                    break;
                }
                default:
                    throw new IOException("Unknown record type: " + f[0]);
            }
        }
    }

    private static String join(String... fields) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) sb.append('\t');
            sb.append(escape(fields[i]));
        }
        return sb.toString();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n").replace("\r", "\\r");
    }

    private static String unescape(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                sb.append(n == 't' ? '\t' : n == 'n' ? '\n' : n == 'r' ? '\r' : n);
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }
}

/* ===================== Sample data (first run only) ===================== */
final class SampleData {
    private SampleData() {}

    static void seed(Bank bank) {
        String[][] people = {
            {"Ahmed Hassan",   "+20 100 123 4567"}, {"Mohamed Ali",   "+20 111 234 5678"},
            {"Mona Saad",      "+20 122 345 6789"}, {"Sara Mahmoud",  "+20 101 456 7890"},
            {"Omar Khaled",    "+20 112 567 8901"}, {"Nour El-Din",   "+20 120 678 9012"},
            {"Hoda Ibrahim",   "+20 109 789 0123"}, {"Youssef Adel",  "+20 115 890 1234"},
            {"Mariam Samir",   "+20 127 901 2345"}, {"Karim Fathy",   "+20 106 012 3456"},
            {"Laila Mostafa",  "+20 114 123 4560"}, {"Tarek Salah",   "+20 102 234 5671"}
        };
        Random rnd = new Random(2026);
        for (int i = 0; i < people.length; i++) {
            Customer c = bank.addCustomer(people[i][0], people[i][1]);
            if (i == people.length - 1) continue;                 // last customer has no account yet
            List<Account> opened = new ArrayList<>();
            opened.add(bank.openAccount(c, "Savings", cents(rnd, 150000, 4500000)));
            if (i % 3 != 2) opened.add(bank.openAccount(c, "Checking", cents(rnd, 20000, 900000)));
            for (Account a : opened) activity(a, rnd);
        }
    }

    /** Random amount between min and max, both given in cents. */
    private static BigDecimal cents(Random rnd, int min, int max) {
        return BigDecimal.valueOf(min + rnd.nextInt(max - min), 2);
    }

    private static void activity(Account a, Random rnd) {
        int n = 1 + rnd.nextInt(4);
        for (int k = 0; k < n; k++) {
            try {
                if (rnd.nextBoolean()) a.deposit(cents(rnd, 5000, 300000));
                else a.withdraw(cents(rnd, 2000, 150000));
            } catch (InsufficientFundsException ex) {
                // rule violation: skip this random transaction
            }
        }
        if (rnd.nextInt(3) == 0) a.monthEnd();
    }
}

/* =====================================================================
 *  UI TOOLKIT  (theme, reusable styled components)
 * ===================================================================== */

/* ===================== Theme ===================== */
final class Theme {
    static final Color NAVY      = new Color(0x123B66);
    static final Color BLUE      = new Color(0x2463EB);
    static final Color BG        = new Color(0xF4F7FB);
    static final Color CARD      = Color.WHITE;
    static final Color TEXT      = new Color(0x172B4D);
    static final Color MUTED     = new Color(0x6B7C93);
    static final Color BORDER    = new Color(0xDCE4F0);
    static final Color ROW_ALT   = new Color(0xF7F9FD);
    static final Color HEADER_BG = new Color(0xEEF3FA);
    static final Color SELECT    = new Color(0xE0EAFF);
    static final Color GREEN     = new Color(0x15A34A);
    static final Color RED       = new Color(0xDC2626);

    private static final String FAMILY = pickFamily();

    private Theme() {}

    private static String pickFamily() {
        Set<String> names = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String f : new String[]{"Segoe UI", "SF Pro Text", "Helvetica Neue", "Inter", "Roboto", "Arial"})
            if (names.contains(f)) return f;
        return Font.SANS_SERIF;
    }

    static Font font(int style, float size) { return new Font(FAMILY, style, 12).deriveFont(style, size); }

    static void aa(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    static Color shade(Color c, double f) {
        return new Color(Math.min(255, (int) (c.getRed() * f)),
                         Math.min(255, (int) (c.getGreen() * f)),
                         Math.min(255, (int) (c.getBlue() * f)));
    }

    static Color alpha(Color c, int a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), a); }

    static String money(BigDecimal v) {
        return (v.signum() < 0 ? "-" : "") + "$" + String.format("%,.2f", v.abs());
    }

    static boolean isDebit(String type) {
        return "WITHDRAWAL".equals(type) || "MONTHLY FEE".equals(type);
    }

    static String signedMoney(String type, BigDecimal amount) {
        return (isDebit(type) ? "-" : "+") + money(amount);
    }
}

/* ===================== Icons (vector, drawn with Java2D) ===================== */
final class Icons {
    private Icons() {}

    /** Draws a 20x20 icon at (x, y). */
    static void draw(Graphics2D g, String name, int x, int y, Color c) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        g2.setColor(c);
        g2.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (name) {
            case "dashboard":
                g2.fillRoundRect(x, y, 8, 12, 3, 3);
                g2.fillRoundRect(x + 11, y, 8, 7, 3, 3);
                g2.fillRoundRect(x + 11, y + 10, 8, 9, 3, 3);
                g2.fillRoundRect(x, y + 15, 8, 4, 3, 3);
                break;
            case "customers":
                g2.fillOval(x + 6, y + 1, 8, 8);
                g2.fill(new Arc2D.Float(x + 1, y + 11, 18, 16, 0, 180, Arc2D.PIE));
                break;
            case "accounts":
                g2.drawRoundRect(x, y + 3, 19, 14, 4, 4);
                g2.fillRect(x + 1, y + 7, 18, 3);
                g2.fillRect(x + 4, y + 12, 5, 2);
                break;
            case "transactions":
                g2.drawLine(x + 1, y + 5, x + 17, y + 5);
                g2.drawLine(x + 13, y + 1, x + 18, y + 5);
                g2.drawLine(x + 13, y + 9, x + 18, y + 5);
                g2.drawLine(x + 18, y + 15, x + 2, y + 15);
                g2.drawLine(x + 6, y + 11, x + 1, y + 15);
                g2.drawLine(x + 6, y + 19, x + 1, y + 15);
                break;
            case "wallet":
                g2.drawRoundRect(x, y + 3, 19, 14, 5, 5);
                g2.fillOval(x + 13, y + 8, 4, 4);
                break;
            default:
                break;
        }
        g2.dispose();
    }
}

/* ===================== Logo ===================== */
class LogoIcon extends JComponent {
    LogoIcon() { setPreferredSize(new Dimension(42, 42)); }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        g2.setColor(Theme.BLUE);
        g2.fillRoundRect(0, 0, 42, 42, 14, 14);
        g2.setColor(Color.WHITE);
        g2.fillPolygon(new int[]{9, 21, 33}, new int[]{17, 9, 17}, 3);   // roof
        g2.fillRect(11, 20, 4, 11);                                      // columns
        g2.fillRect(19, 20, 4, 11);
        g2.fillRect(27, 20, 4, 11);
        g2.fillRect(8, 33, 26, 3);                                       // base
        g2.dispose();
    }
}

/* ===================== Card (white panel with soft shadow) ===================== */
class Card extends JPanel {
    static final int SH = 5;

    Card(LayoutManager lm, int pad) {
        super(lm);
        setOpaque(false);
        setBorder(new EmptyBorder(pad + SH, pad + SH, pad + SH + 1, pad + SH));
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        int w = getWidth(), h = getHeight();
        for (int i = 0; i < SH; i++) {                      // layered soft shadow
            g2.setColor(new Color(18, 59, 102, 3 + i * 3));
            g2.fill(new RoundRectangle2D.Float(i, i + 1, w - 2 * i, h - 2 * i - 1, 22 - i, 22 - i));
        }
        g2.setColor(Theme.CARD);
        g2.fill(new RoundRectangle2D.Float(SH, SH - 1, w - 2 * SH, h - 2 * SH, 16, 16));
        g2.setColor(Theme.BORDER);
        g2.draw(new RoundRectangle2D.Float(SH, SH - 1, w - 2 * SH - 1, h - 2 * SH - 1, 16, 16));
        g2.dispose();
    }
}

/* ===================== Rounded border ===================== */
class RoundedBorder extends AbstractBorder {
    private final Color color;
    private final int arc;

    RoundedBorder(Color color, int arc) { this.color = color; this.arc = arc; }

    @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        g2.setColor(color);
        g2.drawRoundRect(x, y, w - 1, h - 1, arc, arc);
        g2.dispose();
    }

    @Override public Insets getBorderInsets(Component c) { return new Insets(1, 1, 1, 1); }
}

/* ===================== Button ===================== */
class Btn extends JButton {
    enum Kind { PRIMARY, SUCCESS, DANGER, SECONDARY }

    private final Kind kind;

    Btn(String text, Kind kind) {
        super(text);
        this.kind = kind;
        setFont(Theme.font(Font.BOLD, 13));
        setForeground(kind == Kind.SECONDARY ? Theme.TEXT : Color.WHITE);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setBorder(new EmptyBorder(0, 20, 0, 20));
        setPreferredSize(new Dimension(getPreferredSize().width, 40));
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        Color base;
        switch (kind) {
            case PRIMARY: base = Theme.BLUE; break;
            case SUCCESS: base = Theme.GREEN; break;
            case DANGER:  base = Theme.RED; break;
            default:      base = Color.WHITE; break;
        }
        ButtonModel m = getModel();
        if (kind == Kind.SECONDARY) {
            if (m.isPressed()) base = new Color(0xE3ECFD);
            else if (m.isRollover()) base = new Color(0xF0F5FF);
        } else {
            if (m.isPressed()) base = Theme.shade(base, 0.8);
            else if (m.isRollover()) base = Theme.shade(base, 0.9);
        }
        g2.setColor(base);
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
        if (kind == Kind.SECONDARY) {
            g2.setColor(Theme.BORDER);
            g2.draw(new RoundRectangle2D.Float(0, 0, getWidth() - 1, getHeight() - 1, 12, 12));
        }
        g2.dispose();
        super.paintComponent(g);
    }
}

/* ===================== Sidebar navigation button ===================== */
class NavButton extends JButton {
    private final String icon;
    private boolean active;

    NavButton(String text, String icon) {
        super(text);
        this.icon = icon;
        setFont(Theme.font(Font.BOLD, 14));
        setHorizontalAlignment(SwingConstants.LEFT);
        setBorder(new EmptyBorder(0, 50, 0, 10));
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setAlignmentX(Component.LEFT_ALIGNMENT);
        setPreferredSize(new Dimension(220, 46));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, 46));
        setActive(false);
    }

    void setActive(boolean a) {
        active = a;
        setForeground(a ? Color.WHITE : new Color(0xB8CAE3));
        repaint();
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        if (active) {
            g2.setColor(Theme.BLUE);
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
        } else if (getModel().isRollover()) {
            g2.setColor(new Color(255, 255, 255, 22));
            g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), getHeight(), 14, 14));
        }
        Icons.draw(g2, icon, 16, (getHeight() - 20) / 2, active ? Color.WHITE : new Color(0xB8CAE3));
        g2.dispose();
        super.paintComponent(g);
    }
}

/* ===================== Notification banner (success / error / info) ===================== */
enum Tone { SUCCESS, ERROR, INFO }

class Banner extends JPanel {
    private final JLabel label = new JLabel();
    private final Timer timer = new Timer(5000, e -> setVisible(false));
    private Color bg = Color.WHITE, fg = Theme.TEXT;

    Banner() {
        super(new BorderLayout());
        setOpaque(false);
        setBorder(new EmptyBorder(12, 20, 26, 16));      // 14px bottom margin is outside the painted area
        label.setFont(Theme.font(Font.BOLD, 13));
        add(label, BorderLayout.CENTER);
        timer.setRepeats(false);
        setVisible(false);
    }

    void show(String message, Tone tone) {
        switch (tone) {
            case SUCCESS: bg = new Color(0xDCFCE7); fg = new Color(0x166534); break;
            case ERROR:   bg = new Color(0xFEE2E2); fg = new Color(0x991B1B); break;
            default:      bg = new Color(0xDBEAFE); fg = new Color(0x1E3A8A); break;
        }
        label.setText(message);
        label.setForeground(fg);
        setVisible(true);
        repaint();
        timer.restart();
    }

    @Override public void setVisible(boolean v) {
        super.setVisible(v);
        Container p = getParent();
        if (p instanceof JComponent) { ((JComponent) p).revalidate(); p.repaint(); }
    }

    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        int h = getHeight() - 14;
        g2.setColor(bg);
        g2.fill(new RoundRectangle2D.Float(0, 0, getWidth(), h, 14, 14));
        g2.setColor(fg);
        g2.fill(new RoundRectangle2D.Float(0, 0, 6, h, 6, 6));
        g2.dispose();
    }
}

/* ===================== Flat scrollbar & combo box UIs ===================== */
class FlatScrollBarUI extends BasicScrollBarUI {
    @Override protected void configureScrollBarColors() {
        thumbColor = new Color(0xC3D0E4);
        trackColor = Theme.CARD;
    }
    private JButton zero() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        return b;
    }
    @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
    @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
    @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) { }
    @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
        if (r.isEmpty() || !scrollbar.isEnabled()) return;
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.aa(g2);
        g2.setColor(thumbColor);
        g2.fillRoundRect(r.x + 2, r.y + 2, r.width - 4, r.height - 4, 8, 8);
        g2.dispose();
    }
}

class FlatComboUI extends BasicComboBoxUI {
    @Override protected JButton createArrowButton() {
        JButton b = new JButton() {
            @Override public void paint(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Theme.aa(g2);
                g2.setColor(Theme.MUTED);
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int cx = getWidth() / 2, cy = getHeight() / 2;
                g2.drawLine(cx - 4, cy - 2, cx, cy + 2);
                g2.drawLine(cx, cy + 2, cx + 4, cy - 2);
                g2.dispose();
            }
        };
        b.setBorder(new EmptyBorder(0, 0, 0, 0));
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(32, 32));
        return b;
    }
    @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean hasFocus) { }
    @Override protected ComboPopup createPopup() {
        BasicComboPopup p = new BasicComboPopup(comboBox);
        p.setBorder(new LineBorder(Theme.BORDER));
        return p;
    }
}

/* ===================== Cell renderers ===================== */
class CellRenderer extends DefaultTableCellRenderer {
    private final Set<Integer> right;
    private final int typeCol, amountCol;

    CellRenderer(Set<Integer> right, int typeCol, int amountCol) {
        this.right = right;
        this.typeCol = typeCol;
        this.amountCol = amountCol;
    }

    @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                                                            boolean focus, int row, int col) {
        super.getTableCellRendererComponent(t, v, sel, false, row, col);
        setBorder(new EmptyBorder(0, 14, 0, 14));
        setHorizontalAlignment(right.contains(col) ? SwingConstants.RIGHT : SwingConstants.LEFT);
        setBackground(sel ? Theme.SELECT : (row % 2 == 0 ? Theme.CARD : Theme.ROW_ALT));
        setForeground(Theme.TEXT);
        setFont(Theme.font(Font.PLAIN, 13));
        if (typeCol >= 0 && (col == typeCol || col == amountCol)) {
            boolean debit = Theme.isDebit(String.valueOf(t.getValueAt(row, typeCol)));
            setForeground(debit ? Theme.RED : Theme.GREEN);
            setFont(Theme.font(Font.BOLD, 13));
        }
        return this;
    }
}

/* ===================== UI factory helpers ===================== */
final class Ui {
    private Ui() {}

    static JLabel label(String text, int style, float size, Color color) {
        JLabel l = new JLabel(text);
        l.setFont(Theme.font(style, size));
        l.setForeground(color);
        return l;
    }

    static JLabel sectionTitle(String text) { return label(text, Font.BOLD, 16, Theme.TEXT); }

    static Border fieldBorder(boolean focused) {
        return new CompoundBorder(new RoundedBorder(focused ? Theme.BLUE : Theme.BORDER, 12),
                                  new EmptyBorder(0, 12, 0, 12));
    }

    static JTextField field(int cols, final String hint) {
        final JTextField f = new JTextField(cols) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getText().isEmpty() && hint != null) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    Theme.aa(g2);
                    g2.setColor(new Color(0x9AA8BC));
                    g2.setFont(getFont());
                    FontMetrics fm = g2.getFontMetrics();
                    Insets in = getInsets();
                    g2.drawString(hint, in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
                    g2.dispose();
                }
            }
        };
        f.setFont(Theme.font(Font.PLAIN, 14));
        f.setForeground(Theme.TEXT);
        f.setCaretColor(Theme.BLUE);
        f.setBorder(fieldBorder(false));
        f.setPreferredSize(new Dimension(f.getPreferredSize().width, 40));
        f.addFocusListener(new FocusAdapter() {
            @Override public void focusGained(FocusEvent e) { f.setBorder(fieldBorder(true)); }
            @Override public void focusLost(FocusEvent e) { f.setBorder(fieldBorder(false)); }
        });
        return f;
    }

    static <T> void styleCombo(JComboBox<T> c, int width) {
        c.setUI(new FlatComboUI());
        c.setFont(Theme.font(Font.PLAIN, 14));
        c.setForeground(Theme.TEXT);
        c.setBackground(Theme.CARD);
        c.setBorder(new RoundedBorder(Theme.BORDER, 12));
        c.setPreferredSize(new Dimension(width, 40));
        c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        c.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, false);
                l.setBorder(new EmptyBorder(8, 12, 8, 12));
                l.setFont(Theme.font(Font.PLAIN, 14));
                l.setForeground(Theme.TEXT);
                l.setBackground(selected ? Theme.SELECT : Theme.CARD);
                return l;
            }
        });
    }

    static JPanel labeled(String text, JComponent c) {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.setOpaque(false);
        p.add(label(text, Font.BOLD, 12, Theme.MUTED), BorderLayout.NORTH);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    static JPanel row(JComponent... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        p.setOpaque(false);
        for (JComponent c : items) p.add(c);
        return p;
    }

    static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    static JTable table(DefaultTableModel m, int typeCol, int amountCol, int... rightCols) {
        final Set<Integer> right = new HashSet<>();
        for (int c : rightCols) right.add(c);

        JTable t = new JTable(m);
        t.setRowHeight(42);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setFillsViewportHeight(true);
        t.setFont(Theme.font(Font.PLAIN, 13));
        t.setForeground(Theme.TEXT);
        t.setBackground(Theme.CARD);
        t.setSelectionBackground(Theme.SELECT);
        t.setSelectionForeground(Theme.TEXT);
        t.setDefaultRenderer(Object.class, new CellRenderer(right, typeCol, amountCol));

        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setPreferredSize(new Dimension(0, 40));
        t.getTableHeader().setDefaultRenderer(new TableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable tbl, Object v, boolean s,
                                                                    boolean f, int r, int c) {
                JLabel l = new JLabel(String.valueOf(v).toUpperCase());
                l.setOpaque(true);
                l.setBackground(Theme.HEADER_BG);
                l.setForeground(Theme.MUTED);
                l.setFont(Theme.font(Font.BOLD, 11));
                l.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.BORDER),
                                               new EmptyBorder(0, 14, 0, 14)));
                l.setHorizontalAlignment(right.contains(c) ? SwingConstants.RIGHT : SwingConstants.LEFT);
                return l;
            }
        });
        return t;
    }

    /** Attaches a live, case-insensitive search box to a table (filters across all columns). */
    static void bindSearch(JTable t, DefaultTableModel m, final JTextField search) {
        final TableRowSorter<DefaultTableModel> sorter = new TableRowSorter<>(m);
        for (int i = 0; i < m.getColumnCount(); i++) sorter.setSortable(i, false);
        t.setRowSorter(sorter);
        final Runnable apply = () -> {
            String q = search.getText().trim();
            if (q.isEmpty()) sorter.setRowFilter(null);
            else sorter.setRowFilter(RowFilter.regexFilter("(?i)" + Pattern.quote(q)));
        };
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { apply.run(); }
            @Override public void removeUpdate(DocumentEvent e) { apply.run(); }
            @Override public void changedUpdate(DocumentEvent e) { apply.run(); }
        });
    }

    static JScrollPane scroll(JTable t) {
        JScrollPane sp = new JScrollPane(t);
        sp.setBorder(new LineBorder(Theme.BORDER));
        sp.getViewport().setBackground(Theme.CARD);
        sp.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        sp.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        return sp;
    }

    /** Card containing a title, an optional search box, and a styled table. */
    static Card tableCard(String title, JTextField search, JTable table) {
        Card card = new Card(new BorderLayout(0, 14), 18);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(sectionTitle(title), BorderLayout.WEST);
        if (search != null) top.add(search, BorderLayout.EAST);
        card.add(top, BorderLayout.NORTH);
        card.add(scroll(table), BorderLayout.CENTER);
        return card;
    }

    /** Card containing a title and a form body. */
    static Card formCard(String title, JComponent body) {
        Card card = new Card(new BorderLayout(0, 14), 18);
        card.add(sectionTitle(title), BorderLayout.NORTH);
        card.add(body, BorderLayout.CENTER);
        return card;
    }
}

/* ===================== Dashboard widgets ===================== */
class StatCard extends Card {
    private final JLabel value = Ui.label("0", Font.BOLD, 26, Theme.TEXT);

    StatCard(String title, final String icon, final Color accent) {
        super(new BorderLayout(16, 0), 16);
        JComponent badge = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Theme.aa(g2);
                g2.setColor(Theme.alpha(accent, 28));
                g2.fillRoundRect(0, 0, 48, 48, 16, 16);
                Icons.draw(g2, icon, 14, 14, accent);
                g2.dispose();
            }
        };
        badge.setPreferredSize(new Dimension(48, 48));
        JPanel text = new JPanel(new GridLayout(2, 1, 0, 2));
        text.setOpaque(false);
        text.add(Ui.label(title.toUpperCase(), Font.BOLD, 11, Theme.MUTED));
        text.add(value);
        add(badge, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
    }

    void setValue(String s) { value.setText(s); }
}

class MixRow extends JPanel {
    private final JLabel detail = Ui.label("", Font.PLAIN, 12, Theme.MUTED);
    private double fraction;

    MixRow(String title, final Color color) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(Ui.label(title, Font.BOLD, 14, Theme.TEXT), BorderLayout.WEST);
        top.add(detail, BorderLayout.EAST);
        JComponent bar = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Theme.aa(g2);
                g2.setColor(Theme.HEADER_BG);
                g2.fillRoundRect(0, 0, getWidth(), 8, 8, 8);
                int w = (int) Math.round(getWidth() * fraction);
                if (w > 0) {
                    g2.setColor(color);
                    g2.fillRoundRect(0, 0, Math.max(w, 8), 8, 8, 8);
                }
                g2.dispose();
            }
        };
        bar.setPreferredSize(new Dimension(10, 8));
        add(top, BorderLayout.NORTH);
        add(bar, BorderLayout.CENTER);
    }

    void set(int count, BigDecimal balance, double fraction) {
        this.fraction = Math.max(0, Math.min(1, fraction));
        detail.setText(count + (count == 1 ? " account  \u2022  " : " accounts  \u2022  ") + Theme.money(balance));
        repaint();
    }
}

/* =====================================================================
 *  MAIN APPLICATION
 * ===================================================================== */
public class BankSystem extends JFrame {
    private final Bank bank = new Bank();
    private final DataStore store = new DataStore(DataStore.defaultPath());
    private String startupNotice = null;
    private Tone startupTone = Tone.INFO;

    /* ---- table models ---- */
    private final DefaultTableModel customerModel = Ui.model("ID", "Name", "Phone", "Accounts");
    private final DefaultTableModel accountModel  = Ui.model("Account No.", "Type", "Owner", "Balance");
    private final DefaultTableModel historyModel  = Ui.model("Txn ID", "Date / Time", "Type", "Amount", "Balance After");
    private final DefaultTableModel recentModel   = Ui.model("Txn ID", "Account", "Date / Time", "Type", "Amount", "Balance After");

    /* ---- shared selectors ---- */
    private final JComboBox<Customer> customerBox = new JComboBox<>();
    private final JComboBox<Account> accountBox = new JComboBox<>();

    /* ---- transaction page labels ---- */
    private final JLabel balanceValue = Ui.label("\u2014", Font.BOLD, 32, Theme.NAVY);
    private final JLabel balanceInfo = Ui.label("Select an account", Font.PLAIN, 13, Theme.MUTED);

    /* ---- dashboard widgets ---- */
    private final StatCard cardCustomers = new StatCard("Total Customers", "customers", Theme.BLUE);
    private final StatCard cardAccounts = new StatCard("Total Accounts", "accounts", new Color(0x7C3AED));
    private final StatCard cardBalance = new StatCard("Total Bank Balance", "wallet", Theme.GREEN);
    private final StatCard cardTxns = new StatCard("Total Transactions", "transactions", new Color(0xEA580C));
    private final MixRow mixSavings = new MixRow("Savings", Theme.BLUE);
    private final MixRow mixChecking = new MixRow("Checking", new Color(0x7C3AED));

    /* ---- shell ---- */
    private final CardLayout pages = new CardLayout();
    private final JPanel pageHost = new JPanel(pages);
    private final Map<String, NavButton> navs = new LinkedHashMap<>();
    private final JLabel titleLabel = Ui.label("", Font.BOLD, 24, Theme.TEXT);
    private final JLabel subtitleLabel = Ui.label("", Font.PLAIN, 13, Theme.MUTED);
    private final Banner banner = new Banner();
    private boolean refreshing = false;

    public BankSystem() {
        super("BankFlow \u2013 Bank Account Management System");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(1380, 840);
        setMinimumSize(new Dimension(1200, 720));
        setLocationRelativeTo(null);
        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout());

        loadOrSeed();

        Ui.styleCombo(customerBox, 280);
        Ui.styleCombo(accountBox, 300);

        pageHost.setOpaque(false);
        pageHost.add(buildDashboardPage(), "Dashboard");
        pageHost.add(buildCustomersPage(), "Customers");
        pageHost.add(buildAccountsPage(), "Accounts");
        pageHost.add(buildTransactionsPage(), "Transactions");

        add(buildSidebar(), BorderLayout.WEST);
        add(buildMain(), BorderLayout.CENTER);

        navigate("Dashboard");
        if (startupNotice != null) banner.show(startupNotice, startupTone);
    }

    /* ================= Shell: sidebar, header, main ================= */

    private JPanel buildSidebar() {
        JPanel side = new JPanel(new BorderLayout());
        side.setBackground(Theme.NAVY);
        side.setPreferredSize(new Dimension(250, 0));

        JPanel brand = new JPanel(new BorderLayout(12, 0));
        brand.setOpaque(false);
        brand.setBorder(new EmptyBorder(26, 22, 26, 20));
        JPanel names = new JPanel(new GridLayout(2, 1, 0, 0));
        names.setOpaque(false);
        names.add(Ui.label("BankFlow", Font.BOLD, 22, Color.WHITE));
        names.add(Ui.label("Account Management", Font.PLAIN, 12, new Color(0x9FB6D6)));
        brand.add(new LogoIcon(), BorderLayout.WEST);
        brand.add(names, BorderLayout.CENTER);

        JPanel nav = new JPanel();
        nav.setLayout(new BoxLayout(nav, BoxLayout.Y_AXIS));
        nav.setOpaque(false);
        nav.setBorder(new EmptyBorder(4, 14, 0, 14));
        JLabel menu = Ui.label("MENU", Font.BOLD, 11, new Color(0x7F9BC0));
        menu.setBorder(new EmptyBorder(0, 8, 10, 0));
        menu.setAlignmentX(Component.LEFT_ALIGNMENT);
        nav.add(menu);
        String[][] items = {{"Dashboard", "dashboard"}, {"Customers", "customers"},
                            {"Accounts", "accounts"}, {"Transactions", "transactions"}};
        for (String[] it : items) {
            final String key = it[0];
            NavButton b = new NavButton(it[0], it[1]);
            b.addActionListener(e -> navigate(key));
            navs.put(key, b);
            nav.add(b);
            nav.add(Box.createVerticalStrut(6));
        }

        JLabel footer = Ui.label("\u00A9 BankFlow  \u2022  v1.0", Font.PLAIN, 11, new Color(0x7F9BC0));
        footer.setBorder(new EmptyBorder(16, 24, 20, 0));

        side.add(brand, BorderLayout.NORTH);
        side.add(nav, BorderLayout.CENTER);
        side.add(footer, BorderLayout.SOUTH);
        return side;
    }

    private JPanel buildMain() {
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(Theme.CARD);
        header.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.BORDER),
                                            new EmptyBorder(18, 32, 18, 32)));
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 3));
        titles.setOpaque(false);
        titles.add(titleLabel);
        titles.add(subtitleLabel);

        String date = LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH));
        JLabel chip = new JLabel(date) {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                Theme.aa(g2);
                g2.setColor(Theme.HEADER_BG);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 20, 20);
                g2.dispose();
                super.paintComponent(g);
            }
        };
        chip.setOpaque(false);
        chip.setFont(Theme.font(Font.BOLD, 13));
        chip.setForeground(Theme.NAVY);
        chip.setBorder(new EmptyBorder(9, 16, 9, 16));
        JPanel chipWrap = new JPanel(new GridBagLayout());   // keeps the chip vertically centred
        chipWrap.setOpaque(false);
        chipWrap.add(chip);

        header.add(titles, BorderLayout.WEST);
        header.add(chipWrap, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout());
        body.setBackground(Theme.BG);
        body.setBorder(new EmptyBorder(22, 27, 22, 27));
        JPanel bannerWrap = new JPanel(new BorderLayout());
        bannerWrap.setOpaque(false);
        bannerWrap.setBorder(new EmptyBorder(0, Card.SH, 0, Card.SH));
        bannerWrap.add(banner, BorderLayout.CENTER);
        body.add(bannerWrap, BorderLayout.NORTH);
        body.add(pageHost, BorderLayout.CENTER);

        JPanel main = new JPanel(new BorderLayout());
        main.add(header, BorderLayout.NORTH);
        main.add(body, BorderLayout.CENTER);
        return main;
    }

    private void navigate(String key) {
        pages.show(pageHost, key);
        for (Map.Entry<String, NavButton> e : navs.entrySet()) e.getValue().setActive(e.getKey().equals(key));
        String subtitle;
        switch (key) {
            case "Customers":
                subtitle = "Register new customers and search your client directory."; break;
            case "Accounts":
                subtitle = "Open Savings and Checking accounts and review balances."; break;
            case "Transactions":
                subtitle = "Deposit, withdraw, run month-end processing and review history."; break;
            default:
                subtitle = "A live overview of your customers, accounts and activity."; break;
        }
        titleLabel.setText(key);
        subtitleLabel.setText(subtitle);
        banner.setVisible(false);
        refresh();
    }

    /* ================= Page 1: Dashboard ================= */

    private JPanel buildDashboardPage() {
        JPanel stats = new JPanel(new GridLayout(1, 4, 12, 0));
        stats.setOpaque(false);
        stats.add(cardCustomers);
        stats.add(cardAccounts);
        stats.add(cardBalance);
        stats.add(cardTxns);

        JTable recent = Ui.table(recentModel, 3, 4, 4, 5);
        Card recentCard = Ui.tableCard("Recent Transactions", null, recent);

        JPanel mix = new JPanel(new GridLayout(2, 1, 0, 16));
        mix.setOpaque(false);
        mix.add(mixSavings);
        mix.add(mixChecking);

        Btn addCustomer = new Btn("Add Customer", Btn.Kind.PRIMARY);
        Btn openAccount = new Btn("Open Account", Btn.Kind.SECONDARY);
        Btn newTxn = new Btn("New Transaction", Btn.Kind.SECONDARY);
        addCustomer.addActionListener(e -> navigate("Customers"));
        openAccount.addActionListener(e -> navigate("Accounts"));
        newTxn.addActionListener(e -> navigate("Transactions"));
        JPanel actions = new JPanel(new GridLayout(3, 1, 0, 10));
        actions.setOpaque(false);
        actions.add(addCustomer);
        actions.add(openAccount);
        actions.add(newTxn);

        Card side = new Card(new BorderLayout(0, 16), 18);
        JPanel sideTop = new JPanel(new BorderLayout(0, 16));
        sideTop.setOpaque(false);
        sideTop.add(Ui.sectionTitle("Account Mix"), BorderLayout.NORTH);
        sideTop.add(mix, BorderLayout.CENTER);
        JPanel sideBottom = new JPanel(new BorderLayout(0, 14));
        sideBottom.setOpaque(false);
        sideBottom.add(Ui.sectionTitle("Quick Actions"), BorderLayout.NORTH);
        sideBottom.add(actions, BorderLayout.CENTER);
        side.add(sideTop, BorderLayout.NORTH);
        side.add(sideBottom, BorderLayout.SOUTH);

        JPanel lower = new JPanel(new GridBagLayout());
        lower.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH;
        gc.weighty = 1;
        gc.gridy = 0;
        gc.gridx = 0;
        gc.weightx = 0.68;
        gc.insets = new Insets(0, 0, 0, 12);
        lower.add(recentCard, gc);
        gc.gridx = 1;
        gc.weightx = 0.32;
        gc.insets = new Insets(0, 0, 0, 0);
        lower.add(side, gc);

        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setOpaque(false);
        p.add(stats, BorderLayout.NORTH);
        p.add(lower, BorderLayout.CENTER);
        return p;
    }

    /* ================= Page 2: Customers ================= */

    private JPanel buildCustomersPage() {
        final JTextField name = Ui.field(20, "Full name");
        final JTextField phone = Ui.field(16, "Phone number");
        Btn add = new Btn("Add Customer", Btn.Kind.PRIMARY);

        ActionListener doAdd = e -> {
            String n = name.getText().trim();
            String ph = phone.getText().trim();
            if (n.isEmpty()) { fail("Customer name is required."); name.requestFocusInWindow(); return; }
            if (n.length() < 2 || !n.matches("[\\p{L} .'\\-]+")) {
                fail("Enter a valid name (letters, spaces, hyphens and apostrophes only).");
                name.requestFocusInWindow();
                return;
            }
            if (ph.isEmpty()) { fail("Phone number is required."); phone.requestFocusInWindow(); return; }
            if (!ph.matches("\\+?[0-9 ()\\-]{7,20}")) {
                fail("Enter a valid phone number (7\u201320 digits; + ( ) - and spaces allowed).");
                phone.requestFocusInWindow();
                return;
            }
            Customer c = bank.addCustomer(n, ph);
            name.setText("");
            phone.setText("");
            refresh();
            customerBox.setSelectedItem(c);
            if (persist()) ok("Customer " + c.getName() + " (" + c.getId() + ") was added successfully.");
        };
        add.addActionListener(doAdd);
        name.addActionListener(doAdd);
        phone.addActionListener(doAdd);

        Card form = Ui.formCard("Add Customer", Ui.row(
                Ui.labeled("FULL NAME", name),
                Ui.labeled("PHONE", phone),
                Ui.labeled(" ", add)));

        JTextField search = Ui.field(22, "Search customers\u2026");
        JTable table = Ui.table(customerModel, -1, -1, 3);
        Ui.bindSearch(table, customerModel, search);
        Card list = Ui.tableCard("Customer Directory", search, table);

        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setOpaque(false);
        p.add(form, BorderLayout.NORTH);
        p.add(list, BorderLayout.CENTER);
        return p;
    }

    /* ================= Page 3: Accounts ================= */

    private JPanel buildAccountsPage() {
        final JComboBox<String> type = new JComboBox<>(new String[]{"Savings", "Checking"});
        Ui.styleCombo(type, 170);
        final JTextField initial = Ui.field(10, "0.00");
        Btn open = new Btn("Open Account", Btn.Kind.PRIMARY);

        ActionListener doOpen = e -> {
            Customer c = (Customer) customerBox.getSelectedItem();
            if (c == null) { fail("Add a customer first, then open an account for them."); return; }
            String text = initial.getText().trim().isEmpty() ? "0" : initial.getText();
            BigDecimal amount = parseAmount(text, true);
            if (amount == null) return;
            Account a = bank.openAccount(c, (String) type.getSelectedItem(), amount);
            initial.setText("");
            refresh();
            accountBox.setSelectedItem(a);
            if (persist()) ok(a.getType() + " account " + a.getAccountNumber() + " opened for " + c.getName()
                    + " with " + Theme.money(amount) + ".");
        };
        open.addActionListener(doOpen);
        initial.addActionListener(doOpen);

        Card form = Ui.formCard("Open New Account", Ui.row(
                Ui.labeled("CUSTOMER", customerBox),
                Ui.labeled("ACCOUNT TYPE", type),
                Ui.labeled("INITIAL DEPOSIT ($)", initial),
                Ui.labeled(" ", open)));

        JTextField search = Ui.field(22, "Search accounts\u2026");
        JTable table = Ui.table(accountModel, -1, -1, 3);
        Ui.bindSearch(table, accountModel, search);
        Card list = Ui.tableCard("All Accounts", search, table);

        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setOpaque(false);
        p.add(form, BorderLayout.NORTH);
        p.add(list, BorderLayout.CENTER);
        return p;
    }

    /* ================= Page 4: Transactions ================= */

    private JPanel buildTransactionsPage() {
        final JTextField amount = Ui.field(10, "0.00");
        Btn deposit = new Btn("Deposit", Btn.Kind.SUCCESS);
        Btn withdraw = new Btn("Withdraw", Btn.Kind.DANGER);
        Btn check = new Btn("Check Balance", Btn.Kind.SECONDARY);
        Btn monthEnd = new Btn("Apply Month-End", Btn.Kind.PRIMARY);

        deposit.addActionListener(e -> doTransaction(amount, true));
        withdraw.addActionListener(e -> doTransaction(amount, false));
        check.addActionListener(e -> {
            Account a = (Account) accountBox.getSelectedItem();
            if (a == null) { fail("Select an account first."); return; }
            showBalance();
            info("Balance of " + a.getAccountNumber() + ": " + Theme.money(a.getBalance()));
        });
        monthEnd.addActionListener(e -> {
            Account a = (Account) accountBox.getSelectedItem();
            if (a == null) { fail("Select an account first."); return; }
            String result = a.monthEnd();                      // polymorphic call
            refresh();
            if (persist()) ok(a.getAccountNumber() + ": " + result);
        });
        accountBox.addActionListener(e -> {
            if (refreshing) return;
            showBalance();
            loadHistory();
        });

        JPanel rows = new JPanel();
        rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        JPanel r1 = Ui.row(Ui.labeled("ACCOUNT", accountBox), Ui.labeled("AMOUNT ($)", amount));
        JPanel r2 = Ui.row(deposit, withdraw, check, monthEnd);
        r1.setAlignmentX(Component.LEFT_ALIGNMENT);
        r2.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.add(r1);
        rows.add(Box.createVerticalStrut(14));
        rows.add(r2);
        Card controls = Ui.formCard("New Transaction", rows);

        Card balanceCard = new Card(new BorderLayout(0, 8), 20);
        JPanel bal = new JPanel(new GridLayout(3, 1, 0, 6));
        bal.setOpaque(false);
        bal.add(Ui.label("CURRENT BALANCE", Font.BOLD, 11, Theme.MUTED));
        bal.add(balanceValue);
        bal.add(balanceInfo);
        balanceCard.add(bal, BorderLayout.CENTER);

        JPanel top = new JPanel(new GridBagLayout());
        top.setOpaque(false);
        GridBagConstraints gc = new GridBagConstraints();
        gc.fill = GridBagConstraints.BOTH;
        gc.gridy = 0;
        gc.gridx = 0;
        gc.weightx = 1;
        gc.weighty = 1;
        gc.insets = new Insets(0, 0, 0, 12);
        top.add(controls, gc);
        gc.gridx = 1;
        gc.weightx = 0;
        gc.insets = new Insets(0, 0, 0, 0);
        balanceCard.setPreferredSize(new Dimension(330, 0));
        top.add(balanceCard, gc);

        JTextField search = Ui.field(22, "Search history\u2026");
        JTable table = Ui.table(historyModel, 2, 3, 3, 4);
        Ui.bindSearch(table, historyModel, search);
        Card history = Ui.tableCard("Transaction History", search, table);

        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setOpaque(false);
        p.add(top, BorderLayout.NORTH);
        p.add(history, BorderLayout.CENTER);
        return p;
    }

    /* ================= Actions ================= */

    private void doTransaction(JTextField field, boolean isDeposit) {
        Account a = (Account) accountBox.getSelectedItem();
        if (a == null) { fail("Select an account first."); return; }
        BigDecimal amt = parseAmount(field.getText(), false);
        if (amt == null) return;
        try {
            if (isDeposit) a.deposit(amt); else a.withdraw(amt);
            field.setText("");
            refresh();
            if (persist()) ok((isDeposit ? "Deposited " : "Withdrew ") + Theme.money(amt) + (isDeposit ? " into " : " from ")
                    + a.getAccountNumber() + ". New balance: " + Theme.money(a.getBalance()));
        } catch (InsufficientFundsException | IllegalArgumentException ex) {
            refresh();
            fail(ex.getMessage());
        }
    }

    /** Returns the parsed amount, or null after showing an error banner. */
    private BigDecimal parseAmount(String text, boolean allowZero) {
        try {
            BigDecimal bd = new BigDecimal(text.trim());
            if (bd.signum() < 0 || (!allowZero && bd.signum() == 0)) {
                fail("Enter a valid positive amount.");
                return null;
            }
            if (bd.scale() > 2) { fail("Amounts can have at most 2 decimal places."); return null; }
            if (bd.compareTo(new BigDecimal("1000000000")) > 0) { fail("Amount is too large."); return null; }
            return bd.setScale(2);
        } catch (NumberFormatException ex) {
            fail("Amount must be a number.");
            return null;
        }
    }

    private void ok(String msg)   { banner.show(msg, Tone.SUCCESS); }
    private void fail(String msg) { banner.show(msg, Tone.ERROR); }
    private void info(String msg) { banner.show(msg, Tone.INFO); }

    private void showBalance() {
        Account a = (Account) accountBox.getSelectedItem();
        if (a == null) {
            balanceValue.setText("\u2014");
            balanceValue.setForeground(Theme.NAVY);
            balanceInfo.setText("Select an account");
        } else {
            balanceValue.setText(Theme.money(a.getBalance()));
            balanceValue.setForeground(a.getBalance().signum() < 0 ? Theme.RED : Theme.NAVY);
            balanceInfo.setText(a.getAccountNumber() + "  \u2022  " + a.getType() + "  \u2022  " + a.getOwner().getName());
        }
    }

    private void loadHistory() {
        historyModel.setRowCount(0);
        Account a = (Account) accountBox.getSelectedItem();
        if (a == null) return;
        List<Transaction> list = a.getHistory();
        Collections.reverse(list);                              // newest first
        for (Transaction t : list)
            historyModel.addRow(new Object[]{t.getId(), t.getTime(), t.getType(),
                    Theme.signedMoney(t.getType(), t.getAmount()), Theme.money(t.getBalanceAfter())});
    }

    private void updateDashboard() {
        List<Customer> customers = bank.getCustomers();
        List<Account> accounts = bank.getAccounts();
        BigDecimal total = BigDecimal.ZERO, savingsBal = BigDecimal.ZERO, checkingBal = BigDecimal.ZERO;
        int savings = 0, checking = 0;
        List<Object[]> txns = new ArrayList<>();
        int txnCount = 0;
        for (Account a : accounts) {
            total = total.add(a.getBalance());
            if (a instanceof SavingsAccount) { savings++; savingsBal = savingsBal.add(a.getBalance()); }
            else { checking++; checkingBal = checkingBal.add(a.getBalance()); }
            for (Transaction t : a.getHistory()) {
                txnCount++;
                txns.add(new Object[]{t.getId(), a.getAccountNumber(), t.getTime(), t.getType(),
                        Theme.signedMoney(t.getType(), t.getAmount()), Theme.money(t.getBalanceAfter())});
            }
        }
        cardCustomers.setValue(String.valueOf(customers.size()));
        cardAccounts.setValue(String.valueOf(accounts.size()));
        cardBalance.setValue(Theme.money(total));
        cardTxns.setValue(String.valueOf(txnCount));

        double n = Math.max(1, accounts.size());
        mixSavings.set(savings, savingsBal, savings / n);
        mixChecking.set(checking, checkingBal, checking / n);

        Collections.sort(txns, (x, y) -> Integer.compare((Integer) y[0], (Integer) x[0]));   // newest first
        recentModel.setRowCount(0);
        for (int i = 0; i < Math.min(8, txns.size()); i++) recentModel.addRow(txns.get(i));
    }

    /** Loads saved data, or creates sample data on first run (or if the saved file is unreadable). */
    private void loadOrSeed() {
        if (store.exists()) {
            try {
                store.load(bank);
                return;
            } catch (IOException | RuntimeException ex) {
                bank.clear();
                try {
                    Files.move(store.getFile(), store.getFile().resolveSibling("bankflow-data.corrupt"),
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException ignored) { }
                startupNotice = "Saved data could not be read. A backup was kept and sample data was loaded.";
                startupTone = Tone.ERROR;
            }
        }
        SampleData.seed(bank);
        try {
            store.save(bank);
            if (startupNotice == null)
                startupNotice = "Welcome to BankFlow! Sample data was loaded. Your changes are saved automatically.";
        } catch (IOException ex) {
            startupNotice = "Sample data loaded, but saving is unavailable: " + ex.getMessage();
            startupTone = Tone.ERROR;
        }
    }

    /** Saves the bank to disk. Shows an error banner and returns false on failure. */
    private boolean persist() {
        try {
            store.save(bank);
            return true;
        } catch (IOException ex) {
            fail("Your change was applied but could not be saved: " + ex.getMessage());
            return false;
        }
    }

    /** Reloads every table, combo box and dashboard figure from the Bank data. */
    private void refresh() {
        refreshing = true;
        try {
            Customer selC = (Customer) customerBox.getSelectedItem();
            Account selA = (Account) accountBox.getSelectedItem();

            customerModel.setRowCount(0);
            customerBox.removeAllItems();
            for (Customer c : bank.getCustomers()) {
                customerModel.addRow(new Object[]{c.getId(), c.getName(),
                        c.getPhone().isEmpty() ? "\u2014" : c.getPhone(), c.getAccounts().size()});
                customerBox.addItem(c);
            }
            accountModel.setRowCount(0);
            accountBox.removeAllItems();
            for (Account a : bank.getAccounts()) {
                accountModel.addRow(new Object[]{a.getAccountNumber(), a.getType(),
                        a.getOwner().getName(), Theme.money(a.getBalance())});
                accountBox.addItem(a);
            }
            if (selC != null) customerBox.setSelectedItem(selC);
            if (selA != null) accountBox.setSelectedItem(selA);
        } finally {
            refreshing = false;
        }
        showBalance();
        loadHistory();
        updateDashboard();
    }

    public static void main(String[] args) {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        SwingUtilities.invokeLater(() -> new BankSystem().setVisible(true));
    }
}