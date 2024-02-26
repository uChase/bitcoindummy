import org.bitcoinj.core.Coin;
import org.bitcoinj.core.InsufficientMoneyException;
import org.bitcoinj.core.NetworkParameters;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.kits.WalletAppKit;
import org.bitcoinj.params.TestNet3Params;
import org.bitcoinj.wallet.SendRequest;
import org.bitcoinj.wallet.UnreadableWalletException;
import org.bitcoinj.wallet.Wallet;
import org.bitcoinj.wallet.listeners.WalletCoinsReceivedEventListener;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.Level;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class Bank {

    // Constants and Global Variables
    private static final String WALLET_FILE_NAME = "bitcoin-wallet";
    private static final String WALLET_FILE_NAME_ETF = "bitcoin-wallet-ETF";
    private static final NetworkParameters params = TestNet3Params.get();
    private static final long RECENT_PERIOD = 24 * 60 * 60 * 1000; // 24 hours in milliseconds
    private static List<Transaction> recentTransactions = new ArrayList<>();
    private static WalletAppKit walletAppKit = null;

    static {
        // Assuming SLF4J is bound to logback
        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger rootLogger = loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.ERROR);
    }

    public static void main(String[] args) throws Exception {
        // Wallet setup
        Wallet wallet = checkOrCreateWallet(params); 
        Wallet etfWallet = createETFWallet(params);

        // Event listener for transactions
        wallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                long currentTime = System.currentTimeMillis();
                long transactionTime = tx.getUpdateTime().getTime();
                if (currentTime - transactionTime <= RECENT_PERIOD) {
                    recentTransactions.add(tx);
                    // System.out.println("New recent transaction wallet: " + tx.getHashAsString());
                    // System.out.println("Transaction amount: " + tx.getValue(wallet).toFriendlyString());
                    // System.out.println("Taxing the transaction: " + tx.getValue(wallet).multiply((long) 0.01).toFriendlyString() );
                    // transferFunds(wallet, etfWallet, tx.getValue(wallet).multiply((long) 0.01)); // Transfer 1% of the transaction amount to ETF wallet

                }
            }
        });

        etfWallet.addCoinsReceivedEventListener(new WalletCoinsReceivedEventListener() {
            @Override
            public void onCoinsReceived(Wallet wallet, Transaction tx, Coin prevBalance, Coin newBalance) {
                long currentTime = System.currentTimeMillis();
                long transactionTime = tx.getUpdateTime().getTime();
                if (currentTime - transactionTime <= RECENT_PERIOD) {
                    recentTransactions.add(tx);
                    System.out.println("New recent transaction ETF: " + tx.getHashAsString());
                }
            }
        });
        
        // Initial setup output
        System.out.println("Wallet info:");
        printWalletAndConnectionInfo(wallet);
        System.out.println("ETF Wallet info:");
        printWalletAndConnectionInfo(etfWallet); 
        boolean transfered = false;
        // Continuous balance check loop
        while (true) {
            System.out.println("Wallet balance (in satoshis): " + wallet.getBalance().value);
            System.out.println("Wallet balance ETF (in satoshis): " + etfWallet.getBalance().value);
            System.out.println("Block height: " + walletAppKit.chain().getBestChainHeight());
            System.out.println("Peers: " + walletAppKit.peerGroup().getConnectedPeers().size());

            // Optionally, clean up old transactions from the list
            long currentTime = System.currentTimeMillis();
            recentTransactions.removeIf(tx -> currentTime - tx.getUpdateTime().getTime() > RECENT_PERIOD);
            
            TimeUnit.SECONDS.sleep(30); // Adjust check interval as needed

            if(!transfered && wallet.getBalance().value > 0){
                transferFunds(wallet, etfWallet, wallet.getBalance().div(10)); // Transfer 1% of the transaction amount to ETF wallet
                transfered = true;
            }
            }
    }

    // Helper Functions
    private static Wallet checkOrCreateWallet(NetworkParameters params) throws IOException, UnreadableWalletException {
        walletAppKit = new WalletAppKit(params, new File("."), WALLET_FILE_NAME);
        walletAppKit.setBlockingStartup(false);
        walletAppKit.startAsync();
        walletAppKit.awaitRunning();        
        walletAppKit.peerGroup().setBloomFilterFalsePositiveRate(0.001); // Example: 0.1% false positive rate

        System.out.println("Wallet address: " + walletAppKit.wallet().currentReceiveAddress().toString());

        File walletFile = new File(WALLET_FILE_NAME + ".wallet");
        
        if (walletFile.exists()) {
            // Wallet exists, load it
            return Wallet.loadFromFile(walletFile);
        } else {
            Wallet wallet = walletAppKit.wallet();
            wallet.saveToFile(walletFile);
            throw new UnreadableWalletException("Wallet not found, created a new one");
        }
    }

    private static Wallet createETFWallet(NetworkParameters params) throws IOException, UnreadableWalletException {
        walletAppKit = new WalletAppKit(params, new File("."), WALLET_FILE_NAME_ETF);
        walletAppKit.setBlockingStartup(false);
        walletAppKit.startAsync();
        walletAppKit.awaitRunning();        
        walletAppKit.peerGroup().setBloomFilterFalsePositiveRate(0.001); // Example: 0.1% false positive rate

        System.out.println("ETF Wallet address: " + walletAppKit.wallet().currentReceiveAddress().toString());

        File walletFile = new File(WALLET_FILE_NAME_ETF + ".wallet");
        
        if (walletFile.exists()) {
            // Wallet exists, load it
            return Wallet.loadFromFile(walletFile);
        } else {
            Wallet wallet = walletAppKit.wallet();
            wallet.saveToFile(walletFile);
            throw new UnreadableWalletException("Wallet not found, created a new one");
        }
    }

    private static void transferFunds(Wallet sourceWallet, Wallet destinationWallet, Coin amount) {
        try {
            System.out.println("Sending " + amount.toFriendlyString() + " to " + destinationWallet.currentReceiveAddress().toString());
            SendRequest request = SendRequest.to(destinationWallet.currentReceiveAddress(), amount);
            request.ensureMinRequiredFee = true; // Ensure transaction includes a minimum fee
            request.feePerKb = Transaction.DEFAULT_TX_FEE; // Optionally set a custom fee rate
            Wallet.SendResult sendResult = sourceWallet.sendCoins(walletAppKit.peerGroup(), request);
            System.out.println("Transaction sent! Transaction hash: " + sendResult.tx.getTxId());
    
            // Optionally, you can broadcast the transaction yourself using a different method/service.
        } catch (InsufficientMoneyException e) {
            System.out.println("Insufficient funds in the source wallet. " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.out.println("An error occurred: " + e.getMessage());
            e.printStackTrace(); // Provides more detail on the error
        }
    }

    private static void printWalletAndConnectionInfo(Wallet wallet) {
        System.out.println("Initial Balance: " + wallet.getBalance().toFriendlyString());
        System.out.println("Network: " + params.getId());
        System.out.println("Connected peers: " + walletAppKit.peerGroup().getConnectedPeers().size());
        System.out.println("Wallet address: " + wallet.currentReceiveAddress().toString());
        System.out.println("Block height: " + walletAppKit.chain().getBestChainHeight());
    }
}