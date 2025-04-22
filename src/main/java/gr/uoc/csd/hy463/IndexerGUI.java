package gr.uoc.csd.hy463;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class IndexerGUI extends JFrame {
    private JTextField inputField;
    private JTextField outputField;
    private JButton browseInput;
    private JButton browseOutput;
    private JButton startButton;
    private JTextArea logArea;

    public IndexerGUI() {
        setTitle("Biomedical Indexer");
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setSize(700, 500);
        setLocationRelativeTo(null);

        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 5, 5, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Input folder selection
        gbc.gridx = 0; gbc.gridy = 0;
        panel.add(new JLabel("Input Folder:"), gbc);
        inputField = new JTextField();
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(inputField, gbc);
        browseInput = new JButton("Browse...");
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(browseInput, gbc);

        // Output folder selection
        gbc.gridx = 0; gbc.gridy = 1;
        panel.add(new JLabel("Index Output Folder:"), gbc);
        outputField = new JTextField();
        gbc.gridx = 1; gbc.weightx = 1.0;
        panel.add(outputField, gbc);
        browseOutput = new JButton("Browse...");
        gbc.gridx = 2; gbc.weightx = 0;
        panel.add(browseOutput, gbc);

        // Start button
        startButton = new JButton("Start Indexing");
        gbc.gridx = 1; gbc.gridy = 2; gbc.gridwidth = 2;
        panel.add(startButton, gbc);

        // Log area
        logArea = new JTextArea();
        logArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(logArea);
        scrollPane.setPreferredSize(new Dimension(650, 350));

        getContentPane().add(panel, BorderLayout.NORTH);
        getContentPane().add(scrollPane, BorderLayout.CENTER);

        // Action Listeners
        browseInput.addActionListener(e -> chooseDirectory(inputField));
        browseOutput.addActionListener(e -> chooseDirectory(outputField));
        startButton.addActionListener(e -> startIndexing());
    }

    private void chooseDirectory(JTextField field) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        int ret = chooser.showOpenDialog(this);
        if (ret == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            field.setText(dir.getAbsolutePath());
        }
    }

    private void startIndexing() {
        String inputDir = inputField.getText().trim();
        String outputDir = outputField.getText().trim();
        if (inputDir.isEmpty() || outputDir.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please select both input and output folders.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File in = new File(inputDir);
        File out = new File(outputDir);
        if (!in.isDirectory() || !out.isDirectory()) {
            JOptionPane.showMessageDialog(this, "Invalid folders specified.", "Error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        startButton.setEnabled(false);
        logArea.setText("");

        SwingWorker<Void, String> worker = new SwingWorker<>() {
            long startTime;
            @Override
            protected Void doInBackground() {
                try {
                    startTime = System.currentTimeMillis();
                    ProcessBuilder pb = new ProcessBuilder(
                            "java",
                            "-cp",
                            System.getProperty("java.class.path"),
                            "gr.uoc.csd.hy463.hy463_phaseA",
                            inputDir
                    );
                    pb.directory(out);
                    pb.redirectErrorStream(true);
                    Process proc = pb.start();

                    BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()));
                    String line;
                    while ((line = reader.readLine()) != null) {
                        publish(line + "\n");
                    }
                    proc.waitFor();
                } catch (Exception ex) {
                    publish("Error: " + ex.getMessage() + "\n");
                }
                return null;
            }
            @Override
            protected void process(java.util.List<String> chunks) {
                for (String s : chunks) logArea.append(s);
            }
            @Override
            protected void done() {
                long elapsed = System.currentTimeMillis() - startTime;
                // parse docs and terms from last log line
                Pattern p = Pattern.compile("Indexing complete: (\\d+) terms, (\\d+) docs\\.");
                Matcher m = p.matcher(logArea.getText());
                String stats = "";
                if (m.find()) {
                    stats = String.format("\n=== Stats ===\nIndexed %s documents, %s unique terms in %.2f seconds.\n",
                            m.group(2), m.group(1), elapsed/1000.0);
                } else {
                    stats = String.format("\nCompleted in %.2f seconds.\n", elapsed/1000.0);
                }
                logArea.append(stats);
                startButton.setEnabled(true);
            }
        };
        worker.execute();
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            IndexerGUI gui = new IndexerGUI();
            gui.setVisible(true);
        });
    }

}
