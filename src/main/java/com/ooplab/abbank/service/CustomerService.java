package com.ooplab.abbank.service;

import com.auth0.jwt.JWT;
import com.auth0.jwt.JWTVerifier;
import com.auth0.jwt.algorithms.Algorithm;
import com.auth0.jwt.interfaces.DecodedJWT;
import com.ooplab.abbank.BankAccount;
import com.ooplab.abbank.Log;
import com.ooplab.abbank.LogType;
import com.ooplab.abbank.User;
import com.ooplab.abbank.dao.BankAccountRepository;
import com.ooplab.abbank.dao.LogRepository;
import com.ooplab.abbank.dao.UserRepository;
import com.ooplab.abbank.serviceinf.CustomerServiceINF;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.text.DecimalFormat;
import java.util.*;

@Service
@Slf4j
@AllArgsConstructor
public class CustomerService implements CustomerServiceINF {

    private final BankAccountService bankAccountService;
    private final UserService userService;
    private final UserRepository userRepository;
    private final LogRepository logRepository;
    private final BankAccountRepository bankAccountRepository;
    private final BCryptPasswordEncoder bCryptPasswordEncoder;

    @Override
    public User getUser(String header) {
        String token = header.substring("Bearer ".length());
        Algorithm algorithm = Algorithm.HMAC256("SECRET".getBytes());
        JWTVerifier verifier = JWT.require(algorithm).build();
        DecodedJWT decodedJWT = verifier.verify(token);
        String username = decodedJWT.getSubject();
        return userRepository.findByUsername(username).orElse(null);
    }

    @Override
    public Map<String, Object>  getInformation(String JWT) {
        Map<String, Object> information = new HashMap<>();
        User user = getUser(JWT);
        information.put("firstName", user.getFirstName());
        information.put("lastName", user.getLastName());
        information.put("email", user.getEmail());
        information.put("numAccounts", String.valueOf(getBankAccounts(JWT).size()));
        List<Map<String, String>> accountsInfo = new ArrayList<>();
        DecimalFormat df = new DecimalFormat("#,##0.00");
        getBankAccounts(JWT).forEach((a)->{
            Map<String, String> account = new HashMap<>();
            account.put("accountNumber",a.getAccountNumber());
            account.put("accountType",a.getAccountType());
            account.put("accountDebt",df.format(a.getAccountDebt()).concat(" AED"));
            account.put("accountBalance",df.format(a.getAccountBalance()).concat(" AED"));
            account.put("accountStatus",a.getAccountStatus());
            accountsInfo.add(account);
        });
        information.put("accounts", accountsInfo);
        return information;
    }

    @Override
    public BigDecimal getDebt(String header) {
        List<BankAccount> accounts = getBankAccounts(header);
        final BigDecimal[] debt = {new BigDecimal(BigInteger.ZERO)};
        accounts.forEach((a) -> debt[0] = debt[0].add(a.getAccountDebt()));
        return debt[0];
    }

    @Override
    public String payDebt(String header, String accountNumber, BigDecimal amount) throws InSufficientFunds {
        if(verifyOwnership(header, accountNumber)) return "An error occurred whilst debt payment!";
        List<BankAccount> accounts = getBankAccounts(header);
        return bankAccountService.payDebt(accountNumber, amount);
    }

    @Override
    public String requestLoan(String header, String accountNumber, BigDecimal amount) {
        if(verifyOwnership(header, accountNumber)) return "An error occurred whilst loan request!";
        return bankAccountService.requestLoan(accountNumber, amount);
    }

    @Override
    public void requestBankAccount(String header, String accountType) {
        // TODO: Push Notification Logic to Bankers
        User user = getUser(header);
        bankAccountService.createAccount(user.getUsername(), accountType);
    }

    @Override
    public String transferMoney(String header, String senderAccount, String receiverAccount, BigDecimal amount) throws InSufficientFunds {
        if(verifyOwnership(header, senderAccount)) return "An error occurred whilst money transfer!@";
        String response = "";
        if(amount.compareTo(BigDecimal.ZERO) > 0)
            response = bankAccountService.transferMoney(senderAccount, receiverAccount, amount);
        return response;
    }

    @Override
    public String editProfile(String header, String mail, String pin, String password) {
        User user = getUser(header);
        if(mail != null){
            user.setEmail(mail);
            userRepository.save(user);
        }
        if(password != null){
            userService.setPassword(user.getUsername(), password);
        }
        if(pin != null){
            userService.setPin(user.getUsername(), pin);
        }
        return "Successfully edited profile!";
    }

    @Override
    public List<Log> getNotifications(String header, Boolean old) {
        User user = getUser(header);
        List<BankAccount> accounts = user.getAccounts();
        List<Log> all = new ArrayList<>();
        List<Log> unseen = new ArrayList<>();
        accounts.forEach((a) -> all.addAll(a.getLogs()));
        all.forEach((log) -> {
            if(log.isLogEnabled()){
                unseen.add(log);
            }
        });
        if(old) return all;
        return unseen;
    }

    @Override
    public void seeNotifications(String header) {
        User user = getUser(header);
        List<BankAccount> accounts = user.getAccounts();
        List<Log> all = new ArrayList<>();
        List<Log> unseen = new ArrayList<>();
        accounts.forEach((a) -> {
            List<Log> accountLogs = a.getLogs();
            accountLogs.forEach((log)->{
                if(log.isLogEnabled()){
                    log.setLogEnabled(false);
                    logRepository.save(log);
                }
            });
            a.setLogs(accountLogs);
            bankAccountRepository.save(a);
        });
    }

    @Override
    public List<BankAccount> getBankAccounts(String header) {
        User user = getUser(header);
        List<BankAccount> accounts = user.getAccounts();
        List<BankAccount> active = new ArrayList<>();
        accounts.forEach((a) -> {
            if(Objects.equals(a.getAccountStatus(), "Active")){
                active.add(a);
            }
        });
        return active;
    }

    public boolean verifyOwnership(String header, String accountNumber){
        List<BankAccount> accounts = getBankAccounts(header);
        final boolean[] r = {false};
        accounts.forEach((a) ->{
            if(a.getAccountNumber().equals(accountNumber))
                r[0] = true;
        });
        return !r[0];
    }

    @Override
    public Map<String,List<Map<String, String>>> requestStatement(String header, String accountNumber) {
        Map<String,List<Map<String, String>>> statement = new HashMap<>();
        if(Objects.equals(accountNumber, "")){
            List<BankAccount> accounts = getBankAccounts(header);
            accounts.forEach((a) -> {
                List<Map<String, String>> logs = bankAccountService.getStatement(a);
                statement.put(a.getAccountNumber(), logs);
            });
        }else{
            BankAccount account = bankAccountService.getAccount(accountNumber);
            if(verifyOwnership(header, account.getAccountNumber()))
                statement.put("Forbidden", new ArrayList<>());
            else{
                List<Map<String, String>> logs = bankAccountService.getStatement(account);
                statement.put(account.getAccountNumber(), logs);
            }
        }
        return statement;
    }
}
