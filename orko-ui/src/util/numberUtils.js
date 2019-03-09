/*
 * Orko
 * Copyright © 2018-2019 Graham Crockford
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
export const isValidNumber = val => !isNaN(val) && val !== "" && val !== null

export const isValidOtp = val =>
  !isNaN(val) && Number(val) >= 100000 && Number(val) <= 999999

export const formatNumber = (x, scale, undefinedValue) => {
  if (!isValidNumber(x)) return undefinedValue
  const negative = x < 0
  if (scale < 0) {
    const split = negative
      ? (-x).toString().split("-")
      : x.toString().split("-")
    if (split.length > 1) {
      var result = Number(x).toFixed(split[1])
      return negative ? -result : result
    } else {
      return negative ? -split[0] : split[0]
    }
  } else {
    return Number(x).toFixed(scale)
  }
}
