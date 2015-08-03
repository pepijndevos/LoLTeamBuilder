# lolteambuilder

Recommend a team based on opponents.

## Installation

Download seed data:

    cd resources
    curl -O "https://s3-us-west-1.amazonaws.com/riot-api/seed_data/matches[1-10].json"
    curl -O "http://ddragon.leagueoflegends.com/cdn/5.14.1/data/en_US/champion.json"

Create database

     initdb pg
     postgres -D pg
     createdb teambuilder

## Usage

    lein ring server

## Options

FIXME: listing of options this app accepts.

## Examples

...

### Bugs

...

### Any Other Sections
### That You Think
### Might be Useful

## License

Copyright © 2015 FIXME

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.
