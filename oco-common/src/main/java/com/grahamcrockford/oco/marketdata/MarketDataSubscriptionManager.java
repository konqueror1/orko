package com.grahamcrockford.oco.marketdata;

import static com.grahamcrockford.oco.marketdata.MarketDataType.OPEN_ORDERS;
import static com.grahamcrockford.oco.marketdata.MarketDataType.ORDERBOOK;
import static com.grahamcrockford.oco.marketdata.MarketDataType.TICKER;
import static com.grahamcrockford.oco.marketdata.MarketDataType.TRADES;
import static java.util.Collections.emptySet;

import java.util.Collection;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Set;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.service.marketdata.MarketDataService;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.orders.DefaultOpenOrdersParamCurrencyPair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSet.Builder;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Multimaps;
import com.google.common.util.concurrent.AbstractExecutionThreadService;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.grahamcrockford.oco.exchange.ExchangeService;
import com.grahamcrockford.oco.exchange.TradeServiceFactory;
import com.grahamcrockford.oco.spi.TickerSpec;
import com.grahamcrockford.oco.util.SafelyDispose;
import com.grahamcrockford.oco.util.Sleep;

import info.bitrich.xchangestream.core.ProductSubscription;
import info.bitrich.xchangestream.core.ProductSubscription.ProductSubscriptionBuilder;
import info.bitrich.xchangestream.core.StreamingExchange;
import info.bitrich.xchangestream.core.StreamingMarketDataService;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.FlowableEmitter;
import io.reactivex.disposables.Disposable;

/**
 * Maintains subscriptions to multiple exchanges' market data, using web sockets where it can
 * and polling where it can't, but this is abstracted away. All clients have access to reactive
 * streams of data to which they are subscribed.
 */
@Singleton
@VisibleForTesting
public class MarketDataSubscriptionManager extends AbstractExecutionThreadService {

  private static final Logger LOGGER = LoggerFactory.getLogger(MarketDataSubscriptionManager.class);
  private static final int ORDERBOOK_DEPTH = 20;
  private static final Set<MarketDataType> STREAMING_MARKET_DATA = ImmutableSet.of(TICKER, TRADES, ORDERBOOK);

  private final ExchangeService exchangeService;
  private final TradeServiceFactory tradeServiceFactory;
  private final Sleep sleep;

  private final AtomicReference<Set<MarketDataSubscription>> nextSubscriptions = new AtomicReference<>();
  private final Multimap<String, MarketDataSubscription> subscriptionsPerExchange = HashMultimap.create();
  private ImmutableSet<MarketDataSubscription> activePolling = ImmutableSet.of();
  private final Multimap<String, Disposable> disposablesPerExchange = HashMultimap.create();

  private final Flowable<TickerEvent> tickers;
  private final AtomicReference<FlowableEmitter<TickerEvent>> tickerEmitter = new AtomicReference<>();
  private final Flowable<OpenOrdersEvent> openOrders;
  private final AtomicReference<FlowableEmitter<OpenOrdersEvent>> openOrdersEmitter = new AtomicReference<>();
  private final Flowable<OrderBookEvent> orderbook;
  private final AtomicReference<FlowableEmitter<OrderBookEvent>> orderBookEmitter = new AtomicReference<>();
  private final Flowable<TradeEvent> trades;
  private final AtomicReference<FlowableEmitter<TradeEvent>> tradesEmitter = new AtomicReference<>();


  @Inject
  @VisibleForTesting
  public MarketDataSubscriptionManager(ExchangeService exchangeService, Sleep sleep, TradeServiceFactory tradeServiceFactory) {
    this.exchangeService = exchangeService;
    this.sleep = sleep;
    this.tradeServiceFactory = tradeServiceFactory;
    this.tickers = Flowable.create((FlowableEmitter<TickerEvent> e) -> tickerEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
    this.openOrders = Flowable.create((FlowableEmitter<OpenOrdersEvent> e) -> openOrdersEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
    this.orderbook = Flowable.create((FlowableEmitter<OrderBookEvent> e) -> orderBookEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
    this.trades = Flowable.create((FlowableEmitter<TradeEvent> e) -> tradesEmitter.set(e.serialize()), BackpressureStrategy.MISSING).share().onBackpressureLatest();
  }


  /**
   * Updates the subscriptions for the specified exchanges on the next loop
   * tick. The delay is to avoid a large number of new subscriptions in quick
   * succession causing rate bans on exchanges. Call with an empty set to cancel
   * all subscriptions. None of the streams (e.g. {@link #getTicker(TickerSpec)}
   * will return anything until this is called, but there is no strict order in
   * which they need to be called.
   *
   * @param byExchange The exchanges and subscriptions for each.
   */
  public void updateSubscriptions(Set<MarketDataSubscription> subscriptions) {
    nextSubscriptions.set(ImmutableSet.copyOf(subscriptions));
  }


  /**
   * Gets the stream of a subscription.  Typed by the caller in
   * an unsafe manner for convenience,
   *
   * @param sub The subscription
   * @return The stream.
   */
  @SuppressWarnings("unchecked")
  public <T> Flowable<T> getSubscription(MarketDataSubscription sub) {
    switch (sub.type()) {
      case OPEN_ORDERS:
        return (Flowable<T>) getOpenOrders(sub.spec());
      case ORDERBOOK:
        return (Flowable<T>) getOrderBook(sub.spec());
      case TICKER:
        return (Flowable<T>) getTicker(sub.spec());
      case TRADES:
        return (Flowable<T>) getTrades(sub.spec());
      default:
        throw new IllegalArgumentException("Unknown market data type");
    }
  }


  /**
   * Gets a stream of tickers.
   *
   * @param spec The ticker specification.
   * @return The ticker stream.
   */
  public Flowable<TickerEvent> getTicker(TickerSpec spec) {
    return tickers.filter(t -> t.spec().equals(spec))
      .doOnNext(t -> {
        if (LOGGER.isDebugEnabled()) logTicker("filtered", t);
      });
  }


  /**
   * Gets a stream of open order lists.
   *
   * @param spec The ticker specification.
   */
  public Flowable<OpenOrdersEvent> getOpenOrders(TickerSpec spec) {
    return openOrders.filter(t -> t.spec().equals(spec));
  }


  /**
   * Gets a stream containing updates to the order book.
   *
   * @param spec The ticker specification.
   */
  public Flowable<OrderBookEvent> getOrderBook(TickerSpec spec) {
    return orderbook.filter(t -> t.spec().equals(spec));
  }


  /**
   * Gets a stream of trades.
   *
   * @param spec The ticker specification.
   */
  public Flowable<TradeEvent> getTrades(TickerSpec spec) {
    return trades.filter(t -> t.spec().equals(spec));
  }


  /**
   * Actually performs the subscription changes. Occurs synchronously in the
   * poll loop.
   */
  private void doSubscriptionChanges() {
    Set<MarketDataSubscription> subscriptions = nextSubscriptions.getAndSet(null);
    try {
      if (subscriptions == null)
        return;

      LOGGER.debug("Updating subscriptions to: " + subscriptions);
      Multimap<String, MarketDataSubscription> byExchange = Multimaps.<String, MarketDataSubscription>index(subscriptions, sub -> sub.spec().exchange());

      // Disconnect any streaming exchanges where the tickers currently
      // subscribed mismatch the ones we want.
      Set<String> unchanged = disconnectChangedExchanges(byExchange);

      // Add new subscriptions
      subscribe(byExchange, unchanged);
    } catch (Throwable t) {
      LOGGER.error("Error updating subscriptions", t);
      nextSubscriptions.compareAndSet(null, subscriptions);
    }
  }


  private Set<String> disconnectChangedExchanges(Multimap<String, MarketDataSubscription> byExchange) {
    Builder<String> unchanged = ImmutableSet.builder();

    List<String> changed = Lists.newArrayListWithCapacity(subscriptionsPerExchange.keySet().size());

    for (Entry<String, Collection<MarketDataSubscription>> entry : subscriptionsPerExchange.asMap().entrySet()) {

      String exchangeName = entry.getKey();
      Collection<MarketDataSubscription> current = entry.getValue();
      Collection<MarketDataSubscription> target = FluentIterable.from(byExchange.get(exchangeName)).filter(s -> !s.type().equals(OPEN_ORDERS)).toSet();

      LOGGER.debug("Exchange {} has {}, wants {}", exchangeName, current, target);

      if (current.equals(target)) {
        unchanged.add(exchangeName);
      } else {
        changed.add(exchangeName);
      }
    }

    changed.forEach(exchangeName -> {
      LOGGER.info("... disconnecting from exchange: {}", exchangeName);
      disconnectExchange(exchangeName);
      subscriptionsPerExchange.removeAll(exchangeName);
      LOGGER.info("... disconnected from exchange: {}", exchangeName);
    });

    return unchanged.build();
  }

  private void disconnectExchange(String exchangeName) {
    StreamingExchange exchange = (StreamingExchange) exchangeService.get(exchangeName);

    SafelyDispose.of(disposablesPerExchange.removeAll(exchangeName));

    exchange.disconnect().blockingAwait();
  }

  private void subscribe(Multimap<String, MarketDataSubscription> byExchange, Set<String> unchanged) {
    final Builder<MarketDataSubscription> pollingBuilder = ImmutableSet.builder();
    byExchange
      .asMap()
      .forEach((exchangeName, subscriptionsForExchange) -> {
        Exchange exchange = exchangeService.get(exchangeName);
        if (isStreamingExchange(exchange)) {
          if (!unchanged.contains(exchangeName)) {
            Collection<MarketDataSubscription> streamingSubscriptions = FluentIterable.from(subscriptionsForExchange).filter(s -> STREAMING_MARKET_DATA.contains(s.type())).toSet();
            if (!streamingSubscriptions.isEmpty()) {
              openSubscriptions(exchangeName, exchange, streamingSubscriptions);
            }
          }
          pollingBuilder.addAll(FluentIterable.from(subscriptionsForExchange).filter(s -> !STREAMING_MARKET_DATA.contains(s.type())).toSet());
        } else {
          pollingBuilder.addAll(subscriptionsForExchange);
        }
      });
    activePolling = pollingBuilder.build();
    LOGGER.debug("Polls now set to: " + activePolling);
  }


  private void openSubscriptions(String exchangeName, Exchange exchange, Collection<MarketDataSubscription> streamingSubscriptions) {
    subscriptionsPerExchange.putAll(exchangeName, streamingSubscriptions);
    subscribeExchange((StreamingExchange)exchange, streamingSubscriptions, exchangeName);

    StreamingMarketDataService streaming = ((StreamingExchange)exchange).getStreamingMarketDataService();
    disposablesPerExchange.putAll(
      exchangeName,
      FluentIterable.from(streamingSubscriptions).transform(sub -> {
        switch (sub.type()) {
          case ORDERBOOK:
            return streaming.getOrderBook(sub.spec().currencyPair())
                .map(t -> OrderBookEvent.create(sub.spec(), t))
                .subscribe(this::onOrderBook, e -> LOGGER.error("Error in order book stream for " + sub, e));
          case TICKER:
            LOGGER.debug("Subscribing to {}", sub.spec());
            return streaming.getTicker(sub.spec().currencyPair())
                .map(t -> TickerEvent.create(sub.spec(), t))
                .subscribe(this::onTicker, e -> LOGGER.error("Error in ticker stream for " + sub, e));
          case TRADES:
            return streaming.getTrades(sub.spec().currencyPair())
                .map(t -> TradeEvent.create(sub.spec(), t))
                .subscribe(this::onTrade, e -> LOGGER.error("Error in trade stream for " + sub, e));
          default:
            throw new IllegalStateException("Unexpected market data type: " + sub.type());
        }
      })
    );
  }

  private void onTicker(TickerEvent e) {
    logTicker("onTicker", e);
    if (tickerEmitter.get() != null)
      tickerEmitter.get().onNext(e);
  }

  private void onTrade(TradeEvent e) {
    if (tradesEmitter.get() != null)
      tradesEmitter.get().onNext(e);
  }

  private void onOrderBook(OrderBookEvent e) {
    if (orderBookEmitter.get() != null)
      orderBookEmitter.get().onNext(e);
  }

  private void onOpenOrders(OpenOrdersEvent e) {
    if (openOrdersEmitter.get() != null)
      openOrdersEmitter.get().onNext(e);
  }

  private void subscribeExchange(StreamingExchange streamingExchange, Collection<MarketDataSubscription> subscriptionsForExchange, String exchangeName) {
    if (subscriptionsForExchange.isEmpty())
      return;
    LOGGER.info("Connecting to exchange: " + exchangeName);
    openConnections(streamingExchange, subscriptionsForExchange);
    LOGGER.info("Connected to exchange: " + exchangeName);
  }

  private void openConnections(StreamingExchange streamingExchange, Collection<MarketDataSubscription> subscriptionsForExchange) {
    ProductSubscriptionBuilder builder = ProductSubscription.create();
    subscriptionsForExchange.stream()
      .forEach(s -> {
        if (s.type().equals(TICKER)) {
          builder.addTicker(s.spec().currencyPair());
        }
        if (s.type().equals(ORDERBOOK)) {
          builder.addOrderbook(s.spec().currencyPair());
        }
        if (s.type().equals(TRADES)) {
          builder.addTrades(s.spec().currencyPair());
        }
      });
    streamingExchange.connect(builder.build()).blockingAwait();
  }

  @Override
  protected void run() {
    Thread.currentThread().setName("Market data subscription manager");
    LOGGER.info("{} started", this);
    while (isRunning()) {

      LOGGER.debug("{} start subscription check", this);
      doSubscriptionChanges();

      LOGGER.debug("{} start poll", this);
      activePolling.forEach(sub -> {
        if (isRunning())
          fetchAndBroadcast(sub);
      });

      LOGGER.debug("{} going to sleep", this);
      try {
        sleep.sleep();
      } catch (InterruptedException e) {
        break;
      }

    }
    updateSubscriptions(emptySet());
    LOGGER.info(this + " stopped");
  }

  private void fetchAndBroadcast(MarketDataSubscription subscription) {
    try {
      TickerSpec spec = subscription.spec();
      MarketDataService marketDataService = exchangeService.get(spec.exchange()).getMarketDataService();
      if (subscription.type().equals(TICKER)) {
        onTicker(TickerEvent.create(spec, marketDataService.getTicker(spec.currencyPair())));
      } else if (subscription.type().equals(ORDERBOOK)) {
        onOrderBook(OrderBookEvent.create(spec, marketDataService.getOrderBook(spec.currencyPair(), ORDERBOOK_DEPTH, ORDERBOOK_DEPTH)));
      } else if (subscription.type().equals(TRADES)) {
        // TODO need to return only the new ones onTrade(TradeEvent.create(spec, marketDataService.getTrades(spec.currencyPair())));
      } else if (subscription.type().equals(OPEN_ORDERS)) {
        TradeService tradeService = tradeServiceFactory.getForExchange(subscription.spec().exchange());
        onOpenOrders(OpenOrdersEvent.create(spec, tradeService.getOpenOrders(new DefaultOpenOrdersParamCurrencyPair(subscription.spec().currencyPair()))));
      }
    } catch (Throwable e) {
      LOGGER.error("Error fetching market data: " + subscription, e);
    }
  }

  private boolean isStreamingExchange(Exchange exchange) {
    return exchange instanceof StreamingExchange;
  }

  private void logTicker(String context, TickerEvent e) {
    LOGGER.debug("Ticker [{}] ({}) {}/{} = {}", context, e.spec().exchange(), e.spec().base(), e.spec().counter(), e.ticker().getLast());
  }
}