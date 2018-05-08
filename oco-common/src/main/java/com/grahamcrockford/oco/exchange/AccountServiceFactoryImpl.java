package com.grahamcrockford.oco.exchange;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.knowm.xchange.currency.Currency;
import org.knowm.xchange.dto.account.AccountInfo;
import org.knowm.xchange.dto.account.FundingRecord;
import org.knowm.xchange.service.account.AccountService;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.knowm.xchange.service.trade.params.WithdrawFundsParams;

import com.google.inject.Inject;
import com.grahamcrockford.oco.OcoConfiguration;

class AccountServiceFactoryImpl implements AccountServiceFactory {

  private final ExchangeService exchangeService;
  private final OcoConfiguration configuration;
  private final AccountService dummyService;

  @Inject
  AccountServiceFactoryImpl(ExchangeService exchangeService, OcoConfiguration configuration) {
    this.exchangeService = exchangeService;
    this.configuration = configuration;
    this.dummyService = new AccountService() {

      @Override
      public String withdrawFunds(Currency currency, BigDecimal amount, String address) throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public String withdrawFunds(WithdrawFundsParams params) throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public String requestDepositAddress(Currency currency, String... args) throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public List<FundingRecord> getFundingHistory(TradeHistoryParams params) throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public AccountInfo getAccountInfo() throws IOException {
        throw new UnsupportedOperationException();
      }

      @Override
      public TradeHistoryParams createFundingHistoryParams() {
        throw new UnsupportedOperationException();
      }
    };
  }

  @Override
  public AccountService getForExchange(String exchange) {
    Map<String, ExchangeConfiguration> exchangeConfig = configuration.getExchanges();
    if (exchangeConfig == null) {
      return dummyService;
    }
    final ExchangeConfiguration exchangeConfiguration = configuration.getExchanges().get(exchange);
    if (exchangeConfiguration == null || StringUtils.isEmpty(exchangeConfiguration.getApiKey())) {
      return dummyService;
    }
    return exchangeService.get(exchange).getAccountService();
  }
}