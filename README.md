<h1 align="center">Confero: Data Compare</h1>
<p align="center">
  <img width="150" src="docs/static/logos/icons_multidb.svg" alt="Confero: Data Compare Utility"/>
</p>

[![License](https://img.shields.io/github/license/CrunchyData/postgres-operator)](LICENSE.md)

# Data Compare Made Simple

In the dynamic landscape of today's information-driven world, the need for efficient and accurate data management has never been more critical. As organizations replicate data from various sources, ensuring data consistency becomes a paramount challenge. Introducing Confero –  a straightforward utility crafted to simplify the data comparison process, providing a robust solution for comparing data across various database platforms.

Why the name Confero? The name is derived from the Latin word "cōnferō," meaning "to bring together."

# Installation

### Requirements
Before initiating the build and installation process, ensure the following prerequisites are met:

1. Java version 11 or higher.
2. Postgres 15 or higher instance to use for the Confero Data Compare repository.
3. Necessary JDBC drivers (Postgres and Oracle currently supported).

### Compile
Once the prerequisites are met, begin by forking the repository and cloning it to your host machine:

```sh
YOUR_GITHUB_UN="<your GitHub username>"
git clone --depth 1 "git@github.com:${YOUR_GITHUB_UN}/conferodc.git"
cd conferodc
```

Compile the Java source:

```sh
mvn clean install
```
### Configure Repository Database
Confero necessitates a hosted Postgres repository. To configure, connect to a Postgres database and execute the provided confero.sql script in the database directory.

# Getting Started

### Defining Table Mapping
The initial step involves defining a set of tables to compare, achieved by inserting rows into the `dc_table` within the Confero repository.


dc_table:
- source_schema: Schema/user that owns the table on the source database.
- source_table: Table name on the source database.
- target_schema:  Schema/user that owns the table on the target database.
- target_table: Table name on the target database.
- table_filter:  Specify a valid predicate that would be used in the where clause of a select sql statement.
- parallel_degree:  Data can be compared by splitting up the work among many threads.  The parallel_degree determines the number of threads.  To use parallel threads, the mod_column value must be specified.
- status: Expected values are 'disabled', which is the default, and 'ready'.
- batch_nbr:  Tables can be grouped into batches and compare jobs executed a batch, or grouping of tables.
- mod_column:  Used in conjunction with the parallel_degree.  The work is divided up among the threads using a mod of the specified column.  Therefore, the value entered must be a single column with a numeric data type.

Example of loading a row into `dc_table`:


```sql
INSERT INTO dc_table (source_schema, source_table, target_schema, target_table, parallel_degree, status, batch_nbr)
  VALUES ('hr','emp','hr','emp',1,'ready',1);
```

### Create `confero.properties`
Copy the `confero.properties.sample`` file to confero.properties and define the repository, source, and target connection parameters.  Refer to the Properties section for more details on the settings.

### Perform Data Compare
With the table mapping defined, execute the comparison and provide the mandatory batch command line argument:

```shell
java -jar conferodc --batch=0
```

Using a batch value of 0 will execute the action for all batches.

### Debug/Recheck Out-of-Sync Rows
If discrepancies are detected, run the comparison with the 'check' option:

```shell
java -jar conferodc --batch=0 --check
```

This recheck process is useful when transactions may be in flight during the initial comparison.  The recheck only checks the rows that have been flagged with a descrepancy.  If the row(s) still does not match, details will be reported.  Otherwise, the row will be cleared and marked in-sync.


# Properties
Properties are categorized into four sections: system, repository, source, and target. Each section has specific properties, as described in detail in the documentation.

### system
- batch-fetch-size: Sets the fetch size for retrieving rows from the source or target database.
- batch-commit-size:  The commit size controls the array size and number of rows concurrently inserted into the dc_source/dc_target staging tables.
- batch-load-size:  Defines the number of loads retrieved before saving to the staging tables.
- observer-throttle:  Set to true or false, instructs the loader threads to pause and wait for the observer thread to catch up before continuing to load more data into the staging tables.
- observer-throttle-size:  Number of rows loaded before the loader thread will sleep and wait for clearance from the observer thread.
- observer-vacuum:  Set to true or false, instructs the observer whether to perform a vacuum on the staging tables during checkpoints.
- same-rdbms-optimization: When the source and target are the same RDBMS, use optimization available to the database platform.  Note, using same RDBMS optimization has minimal version requirements on the target platform.

### repository
- repo-host: Host name of server hosting the Postgres repository database.
- repo-port:  Repository Postgres instance port.
- repo-dbname:  Repository database name.
- repo-user:  Postgres database username.
- repo-password:  Postgres database user password.
- repo-schema:  Name of schema that owns the repository tables.

### source
- source-name:  User defined name for the source.
- source-type:  Database type: oracle, postgres
- source-host:  Database server name.
- source-port:  Database port.
- source-dbname:  Database or service name.
- source-user:   Database username.
- source-password:  Database password.

### target
- target-name:  User defined name for the target.
- target-type:  Database type: oracle, postgres
- target-host:  Database server name.
- target-port:  Database port.
- target-dbname:  Database or service name.
- target-user:  Database username.
- target-password:  Database password.


# Data Compare
Confero stores a hash representation of primary key columns and other table columns, reducing row size and storage demands. The utility optimizes network traffic and speeds up the process by using hash functions when comparing similar platforms.

## Hash Options
By default, the data is normalized and the hash performed by the Java code.  When comparing like platforms (oracel to oracle or postgres to postgres), hash functions are used in the database to reduce the amount of network traffic and to speed up the process.

## Processes
Each comparison involves at least three threads: one for the observer and two for the source and target loader processes. By specifying a mod_column in the dc_tables and increasing parallel_degree, the number of threads can be increased to speed up comparison. Tuning between batch sizes, commit rates, and parallel degree is essential for optimal performance.


Confero project source code is available subject to the [Apache 2.0 license](LICENSE.md).