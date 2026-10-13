package com.elflive.app.core

// The exact query the BasketNews fantasy site sends when you open a team
// (copied from the extension's lineupQuery.js).
object LineupQuery {
    const val OPERATION = "fantasyTeamLineupViewRecordFromClient"

    val QUERY: String = """query fantasyTeamLineupViewRecordFromClient(${'$'}locale: String!, ${'$'}leagueId: String!, ${'$'}fantasyTeamId: String!, ${'$'}fantasyRound: Int!, ${'$'}teamGamesCurrentRound: Boolean, ${'$'}pointCalcSystem: String) {
  fantasyTeamLineupRecordFromClient(
    fantasyTeamId: ${'$'}fantasyTeamId
    fantasyRound: ${'$'}fantasyRound
    editMode: false
  ) {
    ...partialFantasyTeamLineupView
    __typename
  }
}

fragment partialFantasyTeamLineupView on FantasyTeamLineup {
  id
  fantasyTeamId
  fantasyTeam {
    title
    leagueId
    wildcards {
      id
      identifier
      fantasyRound
      __typename
    }
    gameMode {
      id
      identifier
      __typename
    }
    __typename
  }
  fantasyRound
  status
  formation
  initialBalance
  remainingBalance
  tradesAllowed
  tradesMade
  players {
    ...partialFantasyTeamLineupViewPlayer
    __typename
  }
  __typename
}

fragment partialFantasyTeamLineupViewPlayer on FantasyTeamLineupPlayer {
  id
  playerId
  player {
    ...partialFantasyTeamLineupViewPlayerFields
    __typename
  }
  cardIdentifier
  position
  captain
  playedAsCardIdentifier
  playedAsCaptain
  __typename
}

fragment partialFantasyTeamLineupViewPlayerFields on Player {
  id
  firstName
  middleName
  lastName
  health
  photo
  team(leagueId: ${'$'}leagueId, fantasyRound: ${'$'}fantasyRound) {
    id
    teamId
    status
    number
    positions
    team {
      id
      logo
      jersey
      jerseyWhite
      abbreviation
      translation(locale: ${'$'}locale) {
        name
        shortName
        __typename
      }
      games(fantasyRound: ${'$'}fantasyRound, currentRound: ${'$'}teamGamesCurrentRound) {
        ...partialGame
        __typename
      }
      __typename
    }
    __typename
  }
  fantasyPrice(leagueId: ${'$'}leagueId, fantasyRound: ${'$'}fantasyRound)
  fantasy_pts(
    leagueId: ${'$'}leagueId
    pointCalcSystem: ${'$'}pointCalcSystem
    fantasyRound: ${'$'}fantasyRound
  )
  __typename
}

fragment partialGame on Game {
  id
  basketnewsApiGameId
  originalGameAt
  team1 {
    points
    team {
      id
      logo
      abbreviation
      translation(locale: ${'$'}locale) {
        name
        shortName
        __typename
      }
      __typename
    }
    __typename
  }
  team2 {
    points
    team {
      id
      logo
      abbreviation
      translation(locale: ${'$'}locale) {
        name
        shortName
        __typename
      }
      __typename
    }
    __typename
  }
  live
  completed
  canceled
  delayed
  __typename
}"""
}
