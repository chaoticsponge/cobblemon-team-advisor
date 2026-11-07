// Usage: node scripts/export-gimmick-moves.cjs /path/to/showdown > src/main/resources/battle-gimmick-moves.json
const path = require('node:path');
const {Dex} = require(path.join(path.resolve(process.argv[2]), 'sim/dex.js'));
const powers = Object.fromEntries(Dex.moves.all().filter(m => !m.isZ && !m.isMax && (m.zMove?.basePower || m.maxMove?.basePower))
    .sort((a, b) => a.id.localeCompare(b.id)).map(m => [m.id, [m.zMove?.basePower || 0, m.maxMove?.basePower || 0]]));
process.stdout.write(JSON.stringify(powers) + '\n');
