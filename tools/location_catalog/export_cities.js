// Dumps cities at/above a population threshold from the `all-the-cities` npm package
// (a repackaging of GeoNames data) as a JSON array on stdout.
// Usage: node export_cities.js <minPopulation>
const cities = require('all-the-cities');
const min = parseInt(process.argv[2] || '500000', 10);
const out = cities
  .filter(c => c.population >= min)
  .map(c => ({
    id: String(c.cityId), name: c.name, cc: c.country, admin1: c.adminCode || '',
    lat: c.loc.coordinates[1], lon: c.loc.coordinates[0], pop: c.population,
  }));
process.stdout.write(JSON.stringify(out));
