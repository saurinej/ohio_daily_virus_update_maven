# Ohio Daily Virus Update (Maven)

This is the maven version of a simple project with the purpose of providing daily updates on the number of confirmed cases of COVID-19 in Ohio through email. Data is gathered
from the Ohio government's website through direct parsing from a link to a CSV file that contains the data for all cases in Ohio. Data is then 
formatted and sent through an email using the Java Mail API. To see the version of this project built in eclipse, see [this](https://github.com/saurinej/ohio_daily_virus_update.git) 
repository.

### Prerequisites

A recent version of maven must be installed. The version of maven used to build this project was 3.6.3. Instructions to install maven can be found 
[here](https://maven.apache.org/install.html).

Note, for the gmail account used to send the emails, "Less secure app access" will likely need to be turned on for this program to work. This can be 
done in the relevant google account [security settings](https://myaccount.google.com/security).

## Getting Started

After cloning the directory, enter the project's directory in the command line. Package the project into an executable jar using

```
$ mvn package
```

After this is complete, a "target" directory will be created in the project's directory. Copy the file "County\_Data\_Over_Time.dat" into the "target"
directory. On the command line, cd into the target directory and there should be an executable jar. To run the program, enter the following command

```
$ java -jar "name_of_runnable_jar_file.jar"
```

## Authors

* **Joseph Saurine** - *Initial work* - (https://github.com/saurinej)

## License

This project is licensed under the MIT License - see the [LICENSE.md](LICENSE.md) file for details

## Acknowledgments

* Thank you Stephen Mills and Ryan Farrar for your help and suggestions.