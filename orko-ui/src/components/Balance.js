import React from "react"
import styled from "styled-components"
import Amount from "./primitives/Amount"
import Para from "./primitives/Para"

const Container = styled.div`
  display: flex;
  align-items: center;
  justify-content: space-between;
  height: 100%;
`

const LOG_10 = Math.log(10)
const log10 = x => Math.log(1 / x) / LOG_10
const scaleOfValue = x => Math.floor(log10(x))

export const Balance = ({ coin, balance, ticker, onClickNumber }) => {
  const noBalances = !balance || !coin
  const noTicker = !ticker

  const noBaseBalance =
    noBalances || (!balance[coin.base] && balance[coin.base] !== 0)
  const noCounterBalance =
    noBalances || (!balance[coin.counter] && balance[coin.counter] !== 0)

  const counterScale = meta =>
    meta.minimumAmount ? scaleOfValue(meta.minimumAmount) : 8

  if (coin) {
    return (
      <Container>
        <Amount name="Balance" fontSize={1} onClick={onClickNumber} coin={coin}>
          {noBaseBalance ? undefined : balance[coin.base].total}
        </Amount>
        <Amount
          name="Can sell"
          fontSize={1}
          onClick={onClickNumber}
          coin={coin}
        >
          {noBaseBalance ? undefined : balance[coin.base].available}
        </Amount>
        <Amount
          name="Sale value at bid"
          deriveScale={counterScale}
          fontSize={1}
          onClick={onClickNumber}
          coin={coin}
        >
          {noBaseBalance || noTicker
            ? undefined
            : balance[coin.base].total * ticker.bid}
        </Amount>
        <Amount
          name={coin.counter + " balance"}
          fontSize={1}
          onClick={onClickNumber}
          coin={coin}
        >
          {noCounterBalance ? undefined : balance[coin.counter].total}
        </Amount>
        <Amount
          name={coin.counter + " available"}
          fontSize={1}
          onClick={onClickNumber}
          coin={coin}
        >
          {noCounterBalance ? undefined : balance[coin.counter].available}
        </Amount>
        <Amount
          name="Can buy"
          deriveScale={counterScale}
          fontSize={1}
          onClick={onClickNumber}
          coin={coin}
        >
          {noCounterBalance || noTicker
            ? undefined
            : balance[coin.counter].available / ticker.ask}
        </Amount>
      </Container>
    )
  } else {
    return <Para>No coin selected</Para>
  }
}

export default Balance
