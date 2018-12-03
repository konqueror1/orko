// ***********************************************
// This example commands.js shows you how to
// create various custom commands and overwrite
// existing commands.
//
// For more comprehensive examples of custom
// commands please read more here:
// https://on.cypress.io/custom-commands
// ***********************************************
//
//
// -- This is a parent command --
// Cypress.Commands.add("login", (email, password) => { ... })
//
//
// -- This is a child command --
// Cypress.Commands.add("drag", { prevSubject: 'element'}, (subject, options) => { ... })
//
//
// -- This is a dual command --
// Cypress.Commands.add("dismiss", { prevSubject: 'optional'}, (subject, options) => { ... })
//
//
// -- This is will overwrite an existing command --
// Cypress.Commands.overwrite("visit", (originalFn, url, options) => { ... })

import {
  IP_WHITELISTING_SECRET,
  IP_WHITELISTING_SECRET_INVALID,
  LOGIN_USER,
  LOGIN_PW,
  LOGIN_SECRET,
  LOGIN_SECRET_INVALID
} from "../util/constants"
import { tokenForSecret } from "../util/token"

function option(options, name) {
  return options === undefined || options[name] === undefined || options[name]
}

Cypress.Commands.add("o", dataAttribute =>
  cy.get("[data-orko='" + dataAttribute + "']")
)

Cypress.Commands.add("secureRequest", options =>
  cy.request({
    ...options,
    headers: {
      "x-xsrf-token": window.localStorage.getItem("x-xsrf-token")
    }
  })
)

Cypress.Commands.add("requestNoFail", (url, options) =>
  cy.request({
    ...options,
    url,
    failOnStatusCode: false
  })
)

Cypress.Commands.add("clearWhitelist", () =>
  cy.request({
    method: "DELETE",
    url: "/api/auth",
    failOnStatusCode: false
  })
)

Cypress.Commands.add("whitelist", options => {
  const valid = option(options, "valid")

  // Clear any existing whitelisting
  cy.clearWhitelist()

  // Re-whitelist
  return cy
    .request({
      method: "PUT",
      url:
        "/api/auth?token=" +
        tokenForSecret(
          valid ? IP_WHITELISTING_SECRET : IP_WHITELISTING_SECRET_INVALID
        ),
      failOnStatusCode: valid
    })
    .should(response => {
      if (valid) {
        expect(response.body).to.eq("Whitelisting successful")
      } else {
        expect(response.status).to.eq(403)
      }
    })
})

Cypress.Commands.add("loginApi", options => {
  const validUser = option(options, "validUser")
  const validPassword = option(options, "validPassword")
  const validToken = option(options, "validToken")
  const valid = validUser && validPassword && validToken

  const body = {
    username: validUser ? LOGIN_USER : LOGIN_USER + "x",
    password: validPassword ? LOGIN_PW : LOGIN_PW + "x",
    secondFactor: tokenForSecret(
      validToken ? LOGIN_SECRET : LOGIN_SECRET_INVALID
    )
  }

  return cy
    .request({
      method: "POST",
      url: "/api/auth/login",
      failOnStatusCode: valid,
      body
    })
    .should(response => {
      if (valid) {
        expect(response.status).to.eq(200)
        expect(response.body).to.have.property("xsrf")
        window.localStorage.setItem("x-xsrf-token", response.body.xsrf)
      } else {
        expect(response.status).to.eq(403)
      }
    })
})

Cypress.Commands.add("login", options => {
  const visit = option(options, "visit")
  const validUser = option(options, "validUser")
  const validPassword = option(options, "validPassword")
  const validToken = option(options, "validToken")
  const hasToken = option(options, "hasToken")
  const valid = validUser && validPassword && validToken && hasToken

  const data = {
    username: validUser ? LOGIN_USER : LOGIN_USER + "x",
    password: validPassword ? LOGIN_PW : LOGIN_PW + "x",
    secondFactor: tokenForSecret(
      validToken ? LOGIN_SECRET : LOGIN_SECRET_INVALID
    )
  }

  if (visit) cy.visit("/")

  cy.o("loginModal")
  cy.o("username").type(data.username)
  cy.o("password").type(data.password)
  if (hasToken) {
    cy.o("secondFactor").type(data.secondFactor)
  }
  cy.o("loginSubmit").click()

  if (valid) {
    cy.o("loginModal").should("not.exist")
    cy.o("errorModal").should("not.exist")
    cy.getCookie("accessToken")
  } else {
    cy.o("errorModal").contains("Error")
    cy.o("errorModal").contains("Login failed")
  }
})

Cypress.Commands.add("logout", () => {
  cy.clearLocalStorage()
})